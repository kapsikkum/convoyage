package app.convoyage.data

/** Display names and handles: what the onboarding step accepts and how a generated handle is shown. Pure. */
object Names {
    const val NAME_MIN = 2
    const val NAME_MAX = 40
    const val HANDLE_MIN = 3
    const val HANDLE_MAX = 30

    /** The server gives a new account `rider_<8 hex>` as both handle and display name. */
    private val GENERATED = Regex("^rider_[0-9a-f]{8}$", RegexOption.IGNORE_CASE)

    fun isGeneratedHandle(text: String) = GENERATED.matches(text.trim())

    /** True when the account still needs the "What should the convoy call you?" step. */
    fun needsOnboarding(displayName: String, handle: String): Boolean {
        val name = displayName.trim()
        return name.isEmpty() || name.equals(handle.trim(), ignoreCase = true) || isGeneratedHandle(name)
    }

    /** Null when [raw] is a fine display name, else a short message for the field. */
    fun nameProblem(raw: String): String? {
        val name = raw.trim()
        return when {
            name.length < NAME_MIN -> "Use at least $NAME_MIN characters"
            name.length > NAME_MAX -> "Use at most $NAME_MAX characters"
            name.any { it.isISOControl() } -> "That name has characters we cannot show"
            name.none { it.isLetterOrDigit() } -> "Include a letter or number"
            isGeneratedHandle(name) -> "Pick a name people will recognise"
            else -> null
        }
    }

    /** Handle is optional: blank is fine (keep the generated one). */
    fun handleProblem(raw: String): String? {
        val handle = raw.trim()
        if (handle.isEmpty()) return null
        return when {
            handle.length < HANDLE_MIN || handle.length > HANDLE_MAX -> "Handles are $HANDLE_MIN to $HANDLE_MAX characters"
            !handle.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' } -> "Letters, numbers and underscores only"
            else -> null
        }
    }

    /** What to show for a member in the roster and chat: a real name, never a raw generated handle. */
    fun friendly(username: String): String {
        val name = username.trim()
        if (name.isEmpty()) return "Rider"
        if (isGeneratedHandle(name)) return "Rider " + name.takeLast(4).uppercase()
        return name
    }

    /** Default convoy name for the Start form, or blank (with a hint) when we have no real name. */
    fun defaultConvoyName(displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty() || isGeneratedHandle(name)) return ""
        return "$name's convoy".take(NAME_MAX)
    }
}
