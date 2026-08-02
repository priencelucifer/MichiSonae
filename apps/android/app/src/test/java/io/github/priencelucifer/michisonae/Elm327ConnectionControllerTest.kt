package io.github.priencelucifer.michisonae

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ConnectionControllerTest {
    @Test
    fun productionControllerExecutesTheStateMachineWithReadOnlyCommands() {
        val transport = FakeElm327Transport()
        val states = Collections.synchronizedList(mutableListOf<Elm327ConnectionState>())
        val ready = CountDownLatch(1)
        val controller = Elm327ConnectionController({ transport }) { state ->
            states += state
            if (state is Elm327ConnectionState.Ready) ready.countDown()
        }

        try {
            controller.start()

            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(controller.state is Elm327ConnectionState.Ready)
            assertEquals(
                listOf(
                    Elm327Command.DISABLE_ECHO,
                    Elm327Command.AUTOMATIC_PROTOCOL,
                    Elm327Command.SUPPORTED_PIDS,
                    Elm327Command.SUPPORTED_PIDS_21_TO_40,
                    Elm327Command.SUPPORTED_PIDS_41_TO_60,
                ),
                transport.queries,
            )
            assertTrue(transport.queries.all { it.isAllowedReadOnlyCommand() })
            assertFalse(transport.closed)
        } finally {
            controller.close()
        }
    }

    @Test
    fun supportedQueryRunsAndMalformedConnectionResponseRetries() {
        val connectedTransport = FakeElm327Transport()
        val queryFinished = CountDownLatch(1)
        val controller = Elm327ConnectionController({ connectedTransport })
        try {
            controller.start()
            waitUntil { controller.state is Elm327ConnectionState.Ready }
            controller.query(Elm327Command.ENGINE_RPM) { result ->
                assertEquals(
                    Elm327Simulator.response(Elm327Command.ENGINE_RPM),
                    result.getOrThrow(),
                )
                queryFinished.countDown()
            }
            assertTrue(queryFinished.await(2, TimeUnit.SECONDS))
        } finally {
            controller.close()
        }

        val waiting = CountDownLatch(1)
        val malformed = Elm327ConnectionController(
            { FakeElm327Transport(malformedInitialization = true) },
        ) { if (it is Elm327ConnectionState.WaitingToRetry) waiting.countDown() }
        try {
            malformed.start()
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertTrue(malformed.state is Elm327ConnectionState.WaitingToRetry)
        } finally {
            malformed.close()
        }
    }

    @Test
    fun transportFailureClosesBeforeRetryAndExposesNoRawException() {
        val transport = FakeElm327Transport(openFailure = IOException("secret device detail"))
        val waiting = CountDownLatch(1)
        val controller = Elm327ConnectionController({ transport }) {
            if (it is Elm327ConnectionState.WaitingToRetry) waiting.countDown()
        }
        try {
            controller.start()
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            val state = controller.state as Elm327ConnectionState.WaitingToRetry
            assertEquals("adapter unavailable", state.reason)
            assertTrue(transport.closed)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeDuringBlockedConnectLeavesControllerStoppedAndTransportClosed() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val transport = object : Elm327Transport {
            @Volatile
            var closed = false

            override fun open() {
                openEntered.countDown()
                releaseOpen.await(2, TimeUnit.SECONDS)
            }

            override fun query(command: Elm327Command): String =
                Elm327Simulator.response(command)

            override fun close() {
                closed = true
                releaseOpen.countDown()
            }
        }
        val controller = Elm327ConnectionController({ transport })

        controller.start()
        assertTrue(openEntered.await(2, TimeUnit.SECONDS))
        controller.close()
        releaseOpen.countDown()

        assertTrue(transport.closed)
        assertEquals(Elm327ConnectionState.Stopped, controller.state)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition was not reached")
    }

    private class FakeElm327Transport(
        private val openFailure: Exception? = null,
        private val malformedInitialization: Boolean = false,
    ) : Elm327Transport {
        val queries = Collections.synchronizedList(mutableListOf<Elm327Command>())
        @Volatile
        var closed = false

        override fun open() {
            openFailure?.let { throw it }
        }

        override fun query(command: Elm327Command): String {
            check(command.isAllowedReadOnlyCommand())
            queries += command
            return if (
                malformedInitialization &&
                command == Elm327Command.DISABLE_ECHO
            ) {
                "garbage"
            } else {
                Elm327Simulator.response(command)
            }
        }

        override fun close() {
            closed = true
        }
    }
}
