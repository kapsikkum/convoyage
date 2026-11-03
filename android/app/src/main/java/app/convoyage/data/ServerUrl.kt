package app.convoyage.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the Server field in Settings accepts. */
object ServerUrl {
    /**
     * `192.168.1.20:8080` becomes `http://192.168.1.20:8080`; a trailing slash and whitespace go; only http and
     * https with a host are valid. Null for anything else (the caller shows "not a valid address").
     */
    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        text = text.trimEnd('/')
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        // A server address has no path of its own to keep ("/v1" would double up), but a reverse-proxy prefix is allowed.
        return text
    }

    /** True for plain-http addresses outside localhost: worth a warning, since traffic and tokens are readable on the network. */
    fun isCleartext(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)
}
