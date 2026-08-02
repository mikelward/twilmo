package app.twilmo.domain.call

/**
 * Where a call attempt failed, at the granularity the on-device debug log
 * records (`AGENTS.md` Privacy: which step failed, one reason per outcome).
 */
enum class CallStep { TOKEN, CONNECT, IN_CALL }

/** One sanitized reason a call ended the way it did. */
data class CallFailure(val step: CallStep, val detail: String)

/**
 * How a finished call ended. Exactly one outcome is reported per call — it
 * is the debug log's record and the UI's explanation.
 */
sealed interface CallOutcome {
    /** Connected, then ended normally by either side. */
    data object Completed : CallOutcome

    /** The user canceled before the call ever connected. */
    data object CanceledBeforeConnect : CallOutcome

    /** Never reached the connected state. */
    data class NeverConnected(val failure: CallFailure) : CallOutcome

    /** Connected, then lost for a reason other than a normal hang-up. */
    data class DroppedMidCall(val failure: CallFailure) : CallOutcome
}

/**
 * The states of one outbound call attempt. One machine instance covers one
 * call, from dial to a terminal [Ended]; the next call starts a fresh
 * machine at [Idle].
 *
 * The machine never holds the dialed number — the driver keeps it — so
 * states, events, and effects can be logged freely under the privacy policy.
 */
sealed interface CallState {
    /** No call yet; waiting for the dial request this machine was made for. */
    data object Idle : CallState

    /**
     * Dial accepted; waiting on a usable access token. A warm cache answers
     * immediately (SPEC "Outbound"), so this state is normally momentary.
     */
    data object PreparingToken : CallState

    /** The calling stack has been told to connect. */
    data object Connecting : CallState

    /** The far end is being alerted. */
    data object Ringing : CallState

    data object Connected : CallState

    /** The stack is re-establishing a connected call after a network blip. */
    data object Reconnecting : CallState

    /**
     * The user asked to end the call; waiting for the stack to confirm.
     * [wasConnected] decides the outcome: a completed call if the call had
     * connected, a canceled attempt if it never did.
     */
    data class Disconnecting(val wasConnected: Boolean) : CallState

    /** Terminal. Every later event is ignored — visibly, never silently. */
    data class Ended(val outcome: CallOutcome) : CallState
}
