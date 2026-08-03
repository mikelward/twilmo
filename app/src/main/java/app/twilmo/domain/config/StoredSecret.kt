package app.twilmo.domain.config

/**
 * A stored client secret together with the authority fingerprint
 * ([AuthorityKeys]) of the endpoint + identity it was saved for. The two are
 * written in one atomic commit, so if the process dies between the secret
 * commit and the config commit of a split-store save, the mismatch between
 * this fingerprint and the surviving config is detectable — [configState]
 * derives the honest "secret needed" state instead of a configured-looking
 * account whose mints all fail.
 *
 * [toString] omits the secret so a logged state object can't carry it into
 * a bug report; the fingerprint is a one-way hash and safe by construction.
 */
data class StoredSecret(
    val secret: String,
    val authorityKey: String,
) {
    override fun toString(): String = "StoredSecret(…)"
}
