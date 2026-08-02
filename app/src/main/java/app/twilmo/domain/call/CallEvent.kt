package app.twilmo.domain.call

/**
 * Everything that can happen to a call, from the user or from the calling
 * stack (the Twilio Voice SDK, named vendor-neutrally here).
 *
 * Details carried on events must already be sanitized by the driver — an
 * error code or class of failure, never a phone number, a contact, or a
 * token — so that anything the machine touches is safe to log.
 */
sealed interface CallEvent {
    /** The user asked to place the call this machine was created for. */
    data object DialRequested : CallEvent

    /** The token client produced a usable access token. */
    data object TokenReady : CallEvent

    /** The token client could not produce a token (no network, bad secret, endpoint down). */
    data class TokenFailed(val detail: String) : CallEvent

    /** The stack reports the far end is being alerted. */
    data object StackRinging : CallEvent

    /** The stack reports the call connected. */
    data object StackConnected : CallEvent

    /** The stack lost media on a connected call and is re-establishing it. */
    data class StackReconnecting(val detail: String) : CallEvent

    /** The stack re-established a call that was reconnecting. */
    data object StackReconnected : CallEvent

    /** The stack gave up before the call connected (rejected, busy, unreachable). */
    data class StackConnectFailed(val detail: String) : CallEvent

    /** The stack reports the call over; [error] is null for a normal end. */
    data class StackDisconnected(val error: String?) : CallEvent

    /** The user asked to end (or cancel) the call. */
    data object HangupRequested : CallEvent
}
