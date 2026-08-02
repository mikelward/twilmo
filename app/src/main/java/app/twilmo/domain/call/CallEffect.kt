package app.twilmo.domain.call

/**
 * Side effects the driver must perform after a transition. The machine
 * decides; the driver executes (token client, calling stack, debug log).
 */
sealed interface CallEffect {
    /** Ask the token client for a usable access token (cache first, mint as fallback). */
    data object RequestToken : CallEffect

    /**
     * Tell the calling stack to connect. The driver supplies the number and
     * token — the machine deliberately never carries either.
     */
    data object ConnectCall : CallEffect

    /** Tell the calling stack to end the call. */
    data object DisconnectCall : CallEffect

    /**
     * Record the call's one outcome: always the debug log, and the UI
     * wherever the user would otherwise have to guess what happened.
     */
    data class ReportOutcome(val outcome: CallOutcome) : CallEffect

    /**
     * An event arrived that this state has no use for. Logged, never
     * silent (`AGENTS.md` principle 1), so a race or a misbehaving driver
     * is visible in the debug log instead of vanishing.
     */
    data class LogIgnoredEvent(val reason: String) : CallEffect
}
