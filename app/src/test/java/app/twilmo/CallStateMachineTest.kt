package app.twilmo

import app.twilmo.domain.call.CallEffect
import app.twilmo.domain.call.CallEvent
import app.twilmo.domain.call.CallFailure
import app.twilmo.domain.call.CallOutcome
import app.twilmo.domain.call.CallState
import app.twilmo.domain.call.CallStateMachine
import app.twilmo.domain.call.CallStep
import app.twilmo.domain.call.CallTransition
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-table coverage of the outbound call state machine: [expectedRows]
 * pins the exact expected transition for every handled (state, event) pair,
 * and every pair not in the table must be visibly ignored — unchanged state
 * plus exactly one [CallEffect.LogIgnoredEvent]. A handled row that
 * regresses to ignored fails its table assertion; a new handled row fails
 * the ignore assertion; a new state or event fails until rows are added.
 */
class CallStateMachineTest {

    private val tokenFailed = CallEvent.TokenFailed("endpoint unreachable")
    private val reconnecting = CallEvent.StackReconnecting("network changed")
    private val connectFailed = CallEvent.StackConnectFailed("busy")
    private val disconnectedClean = CallEvent.StackDisconnected(null)
    private val disconnectedError = CallEvent.StackDisconnected("transport lost")

    private val allStates = listOf(
        CallState.Idle,
        CallState.PreparingToken,
        CallState.Connecting,
        CallState.Ringing,
        CallState.Connected,
        CallState.Reconnecting,
        CallState.Disconnecting(wasConnected = false),
        CallState.Disconnecting(wasConnected = true),
        CallState.Ended(CallOutcome.Completed),
    )

    private val allEvents = listOf(
        CallEvent.DialRequested,
        CallEvent.TokenReady,
        tokenFailed,
        CallEvent.StackRinging,
        CallEvent.StackConnected,
        reconnecting,
        CallEvent.StackReconnected,
        connectFailed,
        disconnectedClean,
        disconnectedError,
        CallEvent.HangupRequested,
    )

    /** Every handled transition, with its exact expected state and effects. */
    private fun expectedRows(): Map<Pair<CallState, CallEvent>, CallTransition> = buildMap {
        val canceling = CallState.Disconnecting(wasConnected = false)
        val hangingUp = CallState.Disconnecting(wasConnected = true)

        row(CallState.Idle, CallEvent.DialRequested, CallState.PreparingToken, CallEffect.RequestToken)

        row(CallState.PreparingToken, CallEvent.TokenReady, CallState.Connecting, CallEffect.ConnectCall)
        end(CallState.PreparingToken, tokenFailed, neverConnected(CallStep.TOKEN, "endpoint unreachable"))
        end(CallState.PreparingToken, CallEvent.HangupRequested, CallOutcome.CanceledBeforeConnect)

        row(CallState.Connecting, CallEvent.StackRinging, CallState.Ringing)
        row(CallState.Connecting, CallEvent.StackConnected, CallState.Connected)
        end(CallState.Connecting, connectFailed, neverConnected(CallStep.CONNECT, "busy"))
        end(CallState.Connecting, disconnectedClean, neverConnected(CallStep.CONNECT, "ended before connecting"))
        end(CallState.Connecting, disconnectedError, neverConnected(CallStep.CONNECT, "transport lost"))
        row(CallState.Connecting, CallEvent.HangupRequested, canceling, CallEffect.DisconnectCall)

        row(CallState.Ringing, CallEvent.StackConnected, CallState.Connected)
        end(CallState.Ringing, connectFailed, neverConnected(CallStep.CONNECT, "busy"))
        end(CallState.Ringing, disconnectedClean, neverConnected(CallStep.CONNECT, "ended before answer"))
        end(CallState.Ringing, disconnectedError, neverConnected(CallStep.CONNECT, "transport lost"))
        row(CallState.Ringing, CallEvent.HangupRequested, canceling, CallEffect.DisconnectCall)

        row(CallState.Connected, reconnecting, CallState.Reconnecting)
        end(CallState.Connected, disconnectedClean, CallOutcome.Completed)
        end(CallState.Connected, disconnectedError, droppedMidCall("transport lost"))
        row(CallState.Connected, CallEvent.HangupRequested, hangingUp, CallEffect.DisconnectCall)

        row(CallState.Reconnecting, CallEvent.StackReconnected, CallState.Connected)
        end(CallState.Reconnecting, disconnectedClean, CallOutcome.Completed)
        end(CallState.Reconnecting, disconnectedError, droppedMidCall("transport lost"))
        row(CallState.Reconnecting, CallEvent.HangupRequested, hangingUp, CallEffect.DisconnectCall)

        // The user asked to end the call: however the stack finishes the
        // teardown, the outcome reflects what the user experienced.
        end(canceling, disconnectedClean, CallOutcome.CanceledBeforeConnect)
        end(canceling, disconnectedError, CallOutcome.CanceledBeforeConnect)
        end(canceling, connectFailed, CallOutcome.CanceledBeforeConnect)
        end(hangingUp, disconnectedClean, CallOutcome.Completed)
        end(hangingUp, disconnectedError, CallOutcome.Completed)
        end(hangingUp, connectFailed, CallOutcome.Completed)
    }

    // --- The whole table, one assertion per pair ---

    @Test
    fun sampleListsCoverEverySealedSubtype() {
        // Ties the hand-written sample lists to the sealed hierarchies: a
        // new CallEvent or CallState subtype fails here until a sample is
        // added, and the completeness check in the table test then forces
        // explicit rows for it. Without this, the reducers' else branches
        // would let a new subtype ship unexercised.
        assertEquals(
            CallEvent::class.sealedSubclasses.toSet(),
            allEvents.map { it::class }.toSet(),
        )
        assertEquals(
            CallState::class.sealedSubclasses.toSet(),
            allStates.map { it::class }.toSet(),
        )
    }

    @Test
    fun everyEventTypeHasAHandledRow() {
        // A new event subtype can satisfy the sealed-coverage check with one
        // sample yet never get a handled row — its whole column would fall
        // to "ignored" and stay green. Every event type must appear in at
        // least one handled row, unless deliberately classified below.
        val alwaysIgnoredEventTypes = emptySet<KClass<out CallEvent>>()
        assertEquals(
            allEvents.map { it::class }.toSet() - alwaysIgnoredEventTypes,
            expectedRows().keys.map { it.second::class }.toSet(),
        )
    }

    @Test
    fun everyStateExceptEndedHasAHandledRow() {
        // Same guarantee for states: only terminal Ended may ignore
        // everything.
        assertEquals(
            allStates.map { it::class }.toSet() - setOf(CallState.Ended::class),
            expectedRows().keys.map { it.first::class }.toSet(),
        )
    }

    @Test
    fun everyPairMatchesTheExpectedTable() {
        val rows = expectedRows()
        var ignoredPairs = 0
        for (state in allStates) {
            for (event in allEvents) {
                val actual = CallStateMachine.reduce(state, event)
                val expected = rows[state to event]
                if (expected != null) {
                    assertEquals("($state, $event)", expected, actual)
                } else {
                    ignoredPairs++
                    assertEquals(
                        "($state, $event) must keep its state when ignored",
                        state,
                        actual.state,
                    )
                    assertEquals(
                        "($state, $event) must emit exactly one effect when ignored",
                        1,
                        actual.effects.size,
                    )
                    assertTrue(
                        "($state, $event) must log the ignored event",
                        actual.effects.single() is CallEffect.LogIgnoredEvent,
                    )
                }
            }
        }
        // The table covers the full cross product: handled + ignored = all.
        assertEquals(allStates.size * allEvents.size, rows.size + ignoredPairs)
    }

    @Test
    fun everyPathIntoEndedReportsExactlyOneOutcome() {
        for (state in allStates) {
            for (event in allEvents) {
                val transition = CallStateMachine.reduce(state, event)
                val ended = transition.state as? CallState.Ended ?: continue
                if (state is CallState.Ended) continue // terminal, no new report
                val reports = transition.effects.filterIsInstance<CallEffect.ReportOutcome>()
                assertEquals(
                    "($state, $event) ended without exactly one reported outcome",
                    1,
                    reports.size,
                )
                assertEquals(ended.outcome, reports.single().outcome)
            }
        }
    }

    @Test
    fun endedIsTerminal() {
        val ended = CallState.Ended(CallOutcome.Completed)
        for (event in allEvents) {
            val transition = CallStateMachine.reduce(ended, event)
            assertEquals(ended, transition.state)
            assertTrue(transition.effects.any { it is CallEffect.LogIgnoredEvent })
        }
    }

    // --- Multi-step scenarios: sequencing the table can't express ---

    @Test
    fun dialToHangupCompletesWithOutcome() {
        var t = CallStateMachine.reduce(CallState.Idle, CallEvent.DialRequested)
        assertEquals(CallState.PreparingToken, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.TokenReady)
        assertEquals(CallState.Connecting, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.StackRinging)
        assertEquals(CallState.Ringing, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.StackConnected)
        assertEquals(CallState.Connected, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.HangupRequested)
        assertEquals(CallState.Disconnecting(wasConnected = true), t.state)

        t = CallStateMachine.reduce(t.state, disconnectedClean)
        assertEquals(CallState.Ended(CallOutcome.Completed), t.state)
    }

    @Test
    fun reconnectRecoversTheCallAndALaterHangupCompletes() {
        var t = CallStateMachine.reduce(CallState.Connected, reconnecting)
        assertEquals(CallState.Reconnecting, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.StackReconnected)
        assertEquals(CallState.Connected, t.state)

        t = CallStateMachine.reduce(t.state, CallEvent.HangupRequested)
        t = CallStateMachine.reduce(t.state, disconnectedClean)
        assertEquals(CallState.Ended(CallOutcome.Completed), t.state)
    }

    @Test
    fun cancelWhileRingingEndsAsCanceledNotCompleted() {
        var t = CallStateMachine.reduce(CallState.Ringing, CallEvent.HangupRequested)
        assertEquals(CallState.Disconnecting(wasConnected = false), t.state)

        t = CallStateMachine.reduce(t.state, disconnectedClean)
        assertEquals(CallState.Ended(CallOutcome.CanceledBeforeConnect), t.state)
    }

    // --- Table helpers ---

    private fun MutableMap<Pair<CallState, CallEvent>, CallTransition>.row(
        from: CallState,
        event: CallEvent,
        to: CallState,
        vararg effects: CallEffect,
    ) {
        put(from to event, CallTransition(to, effects.toList()))
    }

    private fun MutableMap<Pair<CallState, CallEvent>, CallTransition>.end(
        from: CallState,
        event: CallEvent,
        outcome: CallOutcome,
    ) {
        put(
            from to event,
            CallTransition(CallState.Ended(outcome), listOf(CallEffect.ReportOutcome(outcome))),
        )
    }

    private fun neverConnected(step: CallStep, detail: String): CallOutcome =
        CallOutcome.NeverConnected(CallFailure(step, detail))

    private fun droppedMidCall(detail: String): CallOutcome =
        CallOutcome.DroppedMidCall(CallFailure(CallStep.IN_CALL, detail))
}
