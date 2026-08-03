package app.twilmo.domain.config

import java.security.MessageDigest

/**
 * Derives the opaque authority fingerprint that binds cached tokens to the
 * credential set that minted them (see `TokenReadiness`): a SHA-256 over
 * the endpoint URL and identity, hex-encoded. The secret is deliberately
 * not an input — rotating it doesn't change *who* mints, so a still-valid
 * token survives a secret rotation — and the fingerprint is one-way, so it
 * is safe to hold in memory next to freely-loggable state (it never appears
 * in logs regardless; `CachedToken.toString` omits it).
 *
 * The fields are length-prefixed before hashing so no pair of
 * (endpoint, identity) values can collide by shifting the boundary.
 */
object AuthorityKeys {

    fun authorityKey(endpointUrl: String, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in listOf(endpointUrl, identity)) {
            val bytes = field.encodeToByteArray()
            digest.update(bytes.size.toString().encodeToByteArray())
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
