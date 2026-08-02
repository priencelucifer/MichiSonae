package io.github.priencelucifer.michisonae

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ConnectionTest {
    @Test
    fun simulatorConnectsAndDiscoversOnlySupportedValues() {
        val ready = Elm327ConnectionSimulator.connect() as Elm327ConnectionState.Ready

        assertTrue(ready.canQuery(Elm327Command.ENGINE_RPM))
        assertTrue(ready.canQuery(Elm327Command.FUEL_LEVEL))
        assertTrue(ready.canQuery(Elm327Command.READ_TROUBLE_CODES))
        assertFalse(ready.canQuery(Elm327Command.SUPPORTED_PIDS))
        assertTrue(ready.statusText.contains("read-only"))
    }

    @Test
    fun connectionFailureClosesAndRetriesWithUserReadableReason() {
        val connecting = Elm327ConnectionMachine.transition(
            Elm327ConnectionState.Stopped,
            Elm327ConnectionEvent.Start,
        ).state
        val failed = Elm327ConnectionMachine.transition(
            connecting,
            Elm327ConnectionEvent.ConnectionFailed("adapter turned off"),
        )
        val waiting = failed.state as Elm327ConnectionState.WaitingToRetry

        assertEquals(2, waiting.nextAttempt)
        assertEquals(1_000L, waiting.delayMs)
        assertTrue(waiting.statusText.contains("adapter turned off"))
        assertEquals(
            listOf(
                Elm327ConnectionAction.CloseSocket,
                Elm327ConnectionAction.ScheduleRetry(1_000L),
            ),
            failed.actions,
        )
        assertEquals(
            Elm327ConnectionState.Connecting(2),
            Elm327ConnectionMachine.transition(
                waiting,
                Elm327ConnectionEvent.RetryElapsed,
            ).state,
        )
    }

    @Test
    fun repeatedFailuresUseBoundedExponentialRetry() {
        var state: Elm327ConnectionState = Elm327ConnectionState.Connecting(1)
        val delays = buildList {
            repeat(8) {
                val failed = Elm327ConnectionMachine.transition(
                    state,
                    Elm327ConnectionEvent.ConnectionFailed("offline"),
                )
                val waiting = failed.state as Elm327ConnectionState.WaitingToRetry
                add(waiting.delayMs)
                assertEquals(
                    listOf(
                        Elm327ConnectionAction.CloseSocket,
                        Elm327ConnectionAction.ScheduleRetry(waiting.delayMs),
                    ),
                    failed.actions,
                )
                state = Elm327ConnectionMachine.transition(
                    waiting,
                    Elm327ConnectionEvent.RetryElapsed,
                ).state
            }
        }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays,
        )
    }

    @Test
    fun corruptInitializationResponseFailsClosedIntoRetry() {
        val waiting = Elm327ConnectionSimulator.connect { command ->
            if (command == Elm327Command.AUTOMATIC_PROTOCOL) "?\r>" else {
                Elm327Simulator.response(command)
            }
        } as Elm327ConnectionState.WaitingToRetry

        assertEquals(2, waiting.nextAttempt)
        assertTrue(waiting.reason.contains("Automatic protocol"))
    }

    @Test
    fun noDataDuringDiscoveryProducesAReadyStateWithNoLivePids() {
        val ready = Elm327ConnectionSimulator.connect { command ->
            if (command == Elm327Command.SUPPORTED_PIDS) "NO DATA\r>" else {
                Elm327Simulator.response(command)
            }
        } as Elm327ConnectionState.Ready

        assertEquals(emptySet<Int>(), ready.supportedPids)
        assertFalse(ready.canQuery(Elm327Command.ENGINE_RPM))
        assertTrue(ready.canQuery(Elm327Command.READ_TROUBLE_CODES))
    }

    @Test
    fun everySendActionIsFromTheReadOnlyWhitelist() {
        Elm327Command.entries.forEach { assertTrue(it.isAllowedReadOnlyCommand()) }

        val first = Elm327ConnectionMachine.transition(
            Elm327ConnectionState.Connecting(1),
            Elm327ConnectionEvent.SocketConnected,
        )
        val send = first.actions.single() as Elm327ConnectionAction.Send
        assertEquals(Elm327Command.DISABLE_ECHO, send.command)
        assertTrue(send.command.isAllowedReadOnlyCommand())
    }

    @Test
    fun stoppedIgnoresStaleFailureAndRetryEvents() {
        listOf(
            Elm327ConnectionEvent.ConnectionFailed("late socket failure"),
            Elm327ConnectionEvent.RetryElapsed,
        ).forEach { event ->
            assertEquals(
                Elm327Transition(Elm327ConnectionState.Stopped),
                Elm327ConnectionMachine.transition(Elm327ConnectionState.Stopped, event),
            )
        }
    }

    @Test
    fun stoppedClosesSocketThatConnectsLate() {
        assertEquals(
            Elm327Transition(
                Elm327ConnectionState.Stopped,
                listOf(Elm327ConnectionAction.CloseSocket),
            ),
            Elm327ConnectionMachine.transition(
                Elm327ConnectionState.Stopped,
                Elm327ConnectionEvent.SocketConnected,
            ),
        )
    }
}
