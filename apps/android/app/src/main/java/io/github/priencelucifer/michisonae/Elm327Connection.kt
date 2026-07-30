package io.github.priencelucifer.michisonae

internal sealed interface Elm327ConnectionState {
    val statusText: String

    data object Stopped : Elm327ConnectionState {
        override val statusText = "OBD adapter is disconnected."
    }

    data class Connecting(val attempt: Int) : Elm327ConnectionState {
        override val statusText = "Connecting to OBD adapter…"
    }

    data class Initializing(
        val attempt: Int,
        val expectedCommand: Elm327Command,
    ) : Elm327ConnectionState {
        override val statusText = "Preparing the read-only OBD connection…"
    }

    data class Discovering(
        val attempt: Int,
        val expectedCommand: Elm327Command,
        val supportedPids: Set<Int>,
    ) : Elm327ConnectionState {
        override val statusText = "Checking which OBD values this car supports…"
    }

    data class Ready(
        val attempt: Int,
        val supportedPids: Set<Int>,
    ) : Elm327ConnectionState {
        override val statusText = "OBD adapter connected in read-only mode."

        fun canQuery(command: Elm327Command): Boolean =
            command == Elm327Command.READ_TROUBLE_CODES ||
                command.mode01Pid?.let { pid ->
                    command !in DISCOVERY_COMMANDS && pid in supportedPids
                } == true
    }

    data class WaitingToRetry(
        val nextAttempt: Int,
        val delayMs: Long,
        val reason: String,
    ) : Elm327ConnectionState {
        override val statusText =
            "OBD connection lost: $reason. Retrying in ${delayMs / 1_000} seconds."
    }
}

internal sealed interface Elm327ConnectionEvent {
    data object Start : Elm327ConnectionEvent
    data object Stop : Elm327ConnectionEvent
    data object SocketConnected : Elm327ConnectionEvent
    data object RetryElapsed : Elm327ConnectionEvent
    data class CommandSucceeded(
        val command: Elm327Command,
        val response: String,
    ) : Elm327ConnectionEvent

    data class ConnectionFailed(val reason: String) : Elm327ConnectionEvent
}

internal sealed interface Elm327ConnectionAction {
    data object OpenSocket : Elm327ConnectionAction
    data object CloseSocket : Elm327ConnectionAction
    data class Send(val command: Elm327Command) : Elm327ConnectionAction
    data class ScheduleRetry(val delayMs: Long) : Elm327ConnectionAction
}

internal data class Elm327Transition(
    val state: Elm327ConnectionState,
    val actions: List<Elm327ConnectionAction> = emptyList(),
)

internal object Elm327ConnectionMachine {
    fun transition(
        state: Elm327ConnectionState,
        event: Elm327ConnectionEvent,
    ): Elm327Transition {
        if (event == Elm327ConnectionEvent.Stop) {
            return Elm327Transition(
                Elm327ConnectionState.Stopped,
                listOf(Elm327ConnectionAction.CloseSocket),
            )
        }
        if (event is Elm327ConnectionEvent.ConnectionFailed) {
            val attempt = when (state) {
                is Elm327ConnectionState.Connecting -> state.attempt
                is Elm327ConnectionState.Initializing -> state.attempt
                is Elm327ConnectionState.Discovering -> state.attempt
                is Elm327ConnectionState.Ready -> state.attempt
                is Elm327ConnectionState.WaitingToRetry -> state.nextAttempt
                Elm327ConnectionState.Stopped -> 1
            }
            val delay = retryDelayMs(attempt)
            return Elm327Transition(
                Elm327ConnectionState.WaitingToRetry(
                    nextAttempt = attempt + 1,
                    delayMs = delay,
                    reason = event.reason.ifBlank { "adapter unavailable" },
                ),
                listOf(
                    Elm327ConnectionAction.CloseSocket,
                    Elm327ConnectionAction.ScheduleRetry(delay),
                ),
            )
        }

        return when {
            state == Elm327ConnectionState.Stopped &&
                event == Elm327ConnectionEvent.Start -> Elm327Transition(
                Elm327ConnectionState.Connecting(attempt = 1),
                listOf(Elm327ConnectionAction.OpenSocket),
            )

            state is Elm327ConnectionState.WaitingToRetry &&
                event == Elm327ConnectionEvent.RetryElapsed -> Elm327Transition(
                Elm327ConnectionState.Connecting(state.nextAttempt),
                listOf(Elm327ConnectionAction.OpenSocket),
            )

            state is Elm327ConnectionState.Connecting &&
                event == Elm327ConnectionEvent.SocketConnected -> Elm327Transition(
                Elm327ConnectionState.Initializing(
                    state.attempt,
                    Elm327Command.DISABLE_ECHO,
                ),
                listOf(Elm327ConnectionAction.Send(Elm327Command.DISABLE_ECHO)),
            )

            state is Elm327ConnectionState.Initializing &&
                event is Elm327ConnectionEvent.CommandSucceeded &&
                event.command == state.expectedCommand -> nextInitialization(state, event)

            state is Elm327ConnectionState.Discovering &&
                event is Elm327ConnectionEvent.CommandSucceeded &&
                event.command == state.expectedCommand -> nextDiscovery(state, event)

            else -> Elm327Transition(state)
        }
    }

    private fun nextInitialization(
        state: Elm327ConnectionState.Initializing,
        event: Elm327ConnectionEvent.CommandSucceeded,
    ): Elm327Transition {
        if (Elm327Parser.response(event.response).status != Elm327ResponseStatus.OK) {
            return transition(
                state,
                Elm327ConnectionEvent.ConnectionFailed(
                    "${event.command.displayName} failed",
                ),
            )
        }
        return if (state.expectedCommand == Elm327Command.DISABLE_ECHO) {
            Elm327Transition(
                state.copy(expectedCommand = Elm327Command.AUTOMATIC_PROTOCOL),
                listOf(Elm327ConnectionAction.Send(Elm327Command.AUTOMATIC_PROTOCOL)),
            )
        } else {
            Elm327Transition(
                Elm327ConnectionState.Discovering(
                    state.attempt,
                    Elm327Command.SUPPORTED_PIDS,
                    emptySet(),
                ),
                listOf(Elm327ConnectionAction.Send(Elm327Command.SUPPORTED_PIDS)),
            )
        }
    }

    private fun nextDiscovery(
        state: Elm327ConnectionState.Discovering,
        event: Elm327ConnectionEvent.CommandSucceeded,
    ): Elm327Transition {
        val response = Elm327Parser.response(event.response)
        if (
            response.status != Elm327ResponseStatus.DATA &&
            response.status != Elm327ResponseStatus.NO_DATA
        ) {
            return transition(
                state,
                Elm327ConnectionEvent.ConnectionFailed(
                    "${event.command.displayName} failed",
                ),
            )
        }
        val supported = state.supportedPids +
            Elm327Parser.supportedPids(event.command, event.response)
        val nextCommand = when {
            event.command == Elm327Command.SUPPORTED_PIDS && 0x20 in supported ->
                Elm327Command.SUPPORTED_PIDS_21_TO_40

            event.command == Elm327Command.SUPPORTED_PIDS_21_TO_40 && 0x40 in supported ->
                Elm327Command.SUPPORTED_PIDS_41_TO_60

            else -> null
        }
        return if (nextCommand == null) {
            Elm327Transition(Elm327ConnectionState.Ready(state.attempt, supported))
        } else {
            Elm327Transition(
                state.copy(expectedCommand = nextCommand, supportedPids = supported),
                listOf(Elm327ConnectionAction.Send(nextCommand)),
            )
        }
    }

    private fun retryDelayMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
}

internal object Elm327ConnectionSimulator {
    fun connect(
        responseFor: (Elm327Command) -> String = Elm327Simulator::response,
    ): Elm327ConnectionState {
        var transition = Elm327ConnectionMachine.transition(
            Elm327ConnectionState.Stopped,
            Elm327ConnectionEvent.Start,
        )
        repeat(12) {
            val action = transition.actions.firstOrNull() ?: return transition.state
            val event = when (action) {
                Elm327ConnectionAction.OpenSocket -> Elm327ConnectionEvent.SocketConnected
                is Elm327ConnectionAction.Send -> Elm327ConnectionEvent.CommandSucceeded(
                    action.command,
                    responseFor(action.command),
                )

                is Elm327ConnectionAction.ScheduleRetry,
                Elm327ConnectionAction.CloseSocket,
                -> return transition.state
            }
            transition = Elm327ConnectionMachine.transition(transition.state, event)
        }
        return transition.state
    }
}

private val DISCOVERY_COMMANDS = setOf(
    Elm327Command.SUPPORTED_PIDS,
    Elm327Command.SUPPORTED_PIDS_21_TO_40,
    Elm327Command.SUPPORTED_PIDS_41_TO_60,
)
