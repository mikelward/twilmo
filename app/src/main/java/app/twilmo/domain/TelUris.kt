package app.twilmo.domain

/**
 * Extracts the dialable number from a `tel:` URI string, or null when there
 * isn't one. This is the entry point of the hand-off intent contract (SPEC
 * "Hand-off intent"): Simmo and other senders launch Twilmo with the number in
 * the URI, E.164 preferred but national format accepted as dialed.
 *
 * Pure Kotlin on purpose — the parse is a correctness surface (a mangled
 * number after the sender already canceled its carrier call strands the user)
 * so it lives in the domain layer where it is exhaustively unit-testable.
 */
fun telUriNumber(uri: String): String? {
    val trimmed = uri.trim()
    if (!trimmed.startsWith(prefix = "tel:", ignoreCase = true)) return null
    val target = trimmed.substring("tel:".length)
        // The number ends where RFC 3966 parameters begin (";isub=", ";ext=",
        // …). The separator is a literal ';' in the URI; an *encoded* %3B is
        // data, not a separator, and the validation below rejects it.
        .substringBefore(';')
        // Senders percent-encode freely — '+' as %2B (Simmo's own hand-off doc
        // records apps doing exactly that to survive query parsing), spaces as
        // %20, parentheses too — so decode the whole target, not just '+'.
        .let { percentDecode(it) ?: return null }
    // Keep the leading '+' and digits; drop visual separators (spaces, dashes,
    // dots, parentheses) that dialers legitimately include. Anything else —
    // letters, a second '+' — means this is not a number Twilmo can dial.
    val builder = StringBuilder()
    for ((index, char) in target.withIndex()) {
        when {
            char.isDigit() -> builder.append(char)
            char == '+' && index == 0 -> builder.append(char)
            char == ' ' || char == '-' || char == '.' || char == '(' || char == ')' -> Unit
            else -> return null
        }
    }
    val number = builder.toString()
    return if (number.isEmpty() || number == "+") null else number
}

/**
 * Decodes %XX escapes, or null on a malformed escape. Hand-rolled rather than
 * URLDecoder because form decoding turns '+' into a space — the opposite of
 * what a tel URI needs, where a raw '+' is the international prefix.
 */
private fun percentDecode(encoded: String): String? {
    val builder = StringBuilder(encoded.length)
    var i = 0
    while (i < encoded.length) {
        val char = encoded[i]
        if (char == '%') {
            if (i + 3 > encoded.length) return null
            val value = encoded.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            builder.append(value.toChar())
            i += 3
        } else {
            builder.append(char)
            i++
        }
    }
    return builder.toString()
}
