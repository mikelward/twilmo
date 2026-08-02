package app.twilmo.domain.call

import app.twilmo.domain.call.CallEffect.ConnectCall
import app.twilmo.domain.call.CallEffect.DisconnectCall
import app.twilmo.domain.call.CallEffect.LogIgnoredEvent
import app.twilmo.domain.call.CallEffect.ReportOutcome
import app.twilmo.domain.call.CallEffect.RequestToken
import app.twilmo.domain.call.CallEvent.DialRequested
import app.twilmo.domain.call.CallEvent.HangupRequested
import app.twilmo.domain.call.CallEvent.StackConnectFailed
import app.twilmo.domain.call.CallEvent.StackConnected
import app.twilmo.domain.call.CallEvent.StackDisconnected
import app.twilmo.domain.call.CallEvent.StackReconnected
import app.twilmo.domain.call.CallEvent.StackReconnecting
import app.twilmo.domain.call.CallEvent.TokenFailed
import app.twilmo.domain.call.CallEvent.TokenReady
import app.twilmo.domain.call.CallOutcome.CanceledBeforeConnect
import app.twilmo.domain.call.CallOutcome.Completed
import app.twilmo.domain.call.CallOutcome.DroppedMidCall
import app.twilmo.domain.call.CallOutcome.NeverConnected
import app.twilmo.domain.call.CallState.Connected
import app.twilmo.domain.call.CallState.Connecting
import app.twilmo.domain.call.CallState.Disconnecting
import app.twilmo.domain.call.CallState.Ended
import app.twilmo.domain.call.CallState.Idle
import app.twilmo.domain.call.CallState.PreparingToken
import app.twilmo.domain.call.CallState.Reconnecting
import app.twilmo.domain.call.CallState.Ringing

/** The result of one transition: the next state and the effects to run. */
data class CallTransition(val state: CallState, val effects: List<CallEffect>)

/**
 * Pure transition table for one outbound call (SPEC "Outbound"; the inbound
 * rows arrive with Phase 3). Total over every (state, event) pair: an
 * unexpected event keeps the state and emits [LogIgnoredEvent] — never an
 * exception, never silence. Every path into [Ended] carries exactly one
 * [ReportOutcome], so no call can finish without a recorded reason.
 */
object CallStateMachine {

    fun reduce(state: CallState, event: CallEvent): CallTransition = when (state) {
        Idle -> reduceIdle(event)
        PreparingToken -> reducePreparingToken(event)
        Connecting -> reduceConnecting(event)
        Ringing -> reduceRinging(event)
        Connected -> reduceConnected(event)
        Reconnecting -> reduceReconnecting(event)
        is Disconnecting -> reduceDisconnecting(state, event)
        is Ended -> ignored(state, event)
    }

    private fun reduceIdle(event: CallEvent): CallTransition = when (event) {
        DialRequested -> CallTransition(PreparingToken, listOf(RequestToken))
        else -> ignored(Idle, event)
    }

    private fun reducePreparingToken(event: CallEvent): CallTransition = when (event) {
        TokenReady -> CallTransition(Connecting, listOf(ConnectCall))
        is TokenFailed ->
            end(NeverConnected(CallFailure(CallStep.TOKEN, event.detail)))
        // Nothing to tear down yet — no connect was issued.
        HangupRequested -> end(CanceledBeforeConnect)
        else -> ignored(PreparingToken, event)
    }

    private fun reduceConnecting(event: CallEvent): CallTransition = when (event) {
        CallEvent.StackRinging -> CallTransition(Ringing, emptyList())
        // The stack may connect without a distinct ringing callback.
        StackConnected -> CallTransition(Connected, emptyList())
        is StackConnectFailed ->
            end(NeverConnected(CallFailure(CallStep.CONNECT, event.detail)))
        is StackDisconnected ->
            end(
                NeverConnected(
                    CallFailure(CallStep.CONNECT, event.error ?: "ended before connecting"),
                ),
            )
        HangupRequested ->
            CallTransition(Disconnecting(wasConnected = false), listOf(DisconnectCall))
        else -> ignored(Connecting, event)
    }

    private fun reduceRinging(event: CallEvent): CallTransition = when (event) {
        StackConnected -> CallTransition(Connected, emptyList())
        is StackConnectFailed ->
            end(NeverConnected(CallFailure(CallStep.CONNECT, event.detail)))
        is StackDisconnected ->
            end(
                NeverConnected(
                    CallFailure(CallStep.CONNECT, event.error ?: "ended before answer"),
                ),
            )
        HangupRequested ->
            CallTransition(Disconnecting(wasConnected = false), listOf(DisconnectCall))
        else -> ignored(Ringing, event)
    }

    private fun reduceConnected(event: CallEvent): CallTransition = when (event) {
        is StackReconnecting -> CallTransition(Reconnecting, emptyList())
        is StackDisconnected -> endOfConnectedCall(event.error)
        HangupRequested ->
            CallTransition(Disconnecting(wasConnected = true), listOf(DisconnectCall))
        else -> ignored(Connected, event)
    }

    private fun reduceReconnecting(event: CallEvent): CallTransition = when (event) {
        StackReconnected -> CallTransition(Connected, emptyList())
        // Same reading as Connected: the stack reports a failed reconnect
        // with an error; a null error is the far end hanging up normally.
        is StackDisconnected -> endOfConnectedCall(event.error)
        HangupRequested ->
            CallTransition(Disconnecting(wasConnected = true), listOf(DisconnectCall))
        else -> ignored(Reconnecting, event)
    }

    private fun reduceDisconnecting(state: Disconnecting, event: CallEvent): CallTransition =
        when (event) {
            // The user asked to end the call; how the stack finishes the
            // teardown (normally, with an error, or as a raced connect
            // failure) doesn't change what the user experienced.
            is StackDisconnected, is StackConnectFailed ->
                end(if (state.wasConnected) Completed else CanceledBeforeConnect)
            else -> ignored(state, event)
        }

    /** A connected call ended: null error is a normal hang-up, anything else a drop. */
    private fun endOfConnectedCall(error: String?): CallTransition = when (error) {
        null -> end(Completed)
        else -> end(DroppedMidCall(CallFailure(CallStep.IN_CALL, error)))
    }

    private fun end(outcome: CallOutcome): CallTransition =
        CallTransition(Ended(outcome), listOf(ReportOutcome(outcome)))

    private fun ignored(state: CallState, event: CallEvent): CallTransition =
        CallTransition(
            state,
            listOf(
                LogIgnoredEvent(
                    "${event::class.simpleName} ignored in ${state::class.simpleName}",
                ),
            ),
        )
}
