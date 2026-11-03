package app.convoyage.data

/** What a link opening the app asks for. */
sealed interface AppLink {
    data class Join(val code: String) : AppLink
    data class Auth(val link: String) : AppLink
}

/** Parses `convoyage://join/<CODE>` and `https://convoyage.app/join/<CODE>` (plus the magic-link scheme). Pure. */
object DeepLink {
    const val CODE_LENGTH = 8

    /** Same alphabet as the server: no 0/O, 1/I/L. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun joinUrl(code: String) = "convoyage://join/$code"
    fun webUrl(code: String) = "https://convoyage.app/join/$code"

    fun shareText(code: String, name: String = ""): String =
        (if (name.isNotBlank()) "Join my convoy \"$name\" on Convoyage.\n" else "Join my convoy on Convoyage.\n") +
            "Code: $code\n${joinUrl(code)}\n${webUrl(code)}"

    /** Forgives case, spaces and dashes in a typed/shared code; null if it is not a valid code. */
    fun normalizeCode(raw: String): String? {
        val code = raw.filter { !it.isWhitespace() && it != '-' }.uppercase()
        return code.takeIf { it.length == CODE_LENGTH && it.all { ch -> ch in ALPHABET } }
    }

    fun parse(uri: String?): AppLink? {
        val text = uri?.trim().orEmpty()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val prefixes = listOf("convoyage://join/", "https://convoyage.app/join/", "https://www.convoyage.app/join/")
        if (lower.startsWith("convoyage://auth")) return AppLink.Auth(text)
        for (p in prefixes) if (lower.startsWith(p)) return joinCode(text.substring(p.length))
        return null
    }

    private fun joinCode(rest: String): AppLink.Join? =
        normalizeCode(rest.substringBefore('?').substringBefore('#').trimEnd('/'))?.let { AppLink.Join(it) }
}
