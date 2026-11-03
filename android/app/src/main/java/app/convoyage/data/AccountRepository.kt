package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.Me
import app.convoyage.api.Tokens
import app.convoyage.api.UpdateMe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What happened to a magic-link request. */
sealed interface MagicLinkOutcome {
    /** The server accepted the email. [devToken] is set only when it runs with MAIL_DEV=1 (verify straight away). */
    data class Sent(val devToken: String?) : MagicLinkOutcome
    data class Failed(val message: String) : MagicLinkOutcome
}

sealed interface SignInOutcome {
    data class SignedIn(val isNewUser: Boolean) : SignInOutcome
    data class Failed(val message: String) : SignInOutcome
}

/** Pulls the one-time token out of a magic link (`convoyage://auth/magic?token=...`) or accepts a bare token. */
fun extractMagicToken(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    Regex("""[?&#]token=([^&#\s]+)""").find(text)?.let {
        return try {
            java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
        } catch (_: Exception) {
            it.groupValues[1]
        }
    }
    // The verify endpoint wants 20..200 chars; anything shorter or with spaces cannot be a token.
    return text.takeIf { it.length in 20..200 && it.none(Char::isWhitespace) && "://" !in it }
}

/** Sign-in, sign-out and the signed-in person's profile. */
class AccountRepository(
    private val api: ConvoyApi,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val _me = MutableStateFlow<Me?>(null)

    /** The last `/v1/me` we fetched (null until loaded or after sign-out). */
    val me: StateFlow<Me?> = _me.asStateFlow()

    suspend fun requestMagicLink(email: String): MagicLinkOutcome = when (val r = api.requestMagicLink(email)) {
        is ApiResult.Ok -> MagicLinkOutcome.Sent(r.value.devLink?.let(::extractMagicToken))
        is ApiResult.Err -> MagicLinkOutcome.Failed(
            when {
                r.isNetwork -> r.message
                r.status == 429 -> "Too many tries. Wait a minute and try again."
                else -> r.message
            },
        )
    }

    /** Verifies a magic-link token, stores the session and loads the profile. */
    suspend fun verify(tokenOrLink: String): SignInOutcome {
        val token = extractMagicToken(tokenOrLink) ?: return SignInOutcome.Failed("That does not look like a sign-in link")
        return when (val r = api.verifyMagicLink(token)) {
            is ApiResult.Err -> SignInOutcome.Failed(
                if (r.status == 401 || r.status == 400) "That sign-in link has expired or was already used. Request a new one." else r.message,
            )
            is ApiResult.Ok -> {
                api.tokens.tokens = Tokens(r.value.accessToken, r.value.refreshToken)
                val me = (api.me() as? ApiResult.Ok)?.value
                _me.value = me
                session.signIn(me?.let(::displayNameOf) ?: "Driver", AuthKind.Account)
                SignInOutcome.SignedIn(r.value.isNewUser)
            }
        }
    }

    /** Loads `/v1/me`. Keeps the old value on failure. */
    suspend fun loadMe(): ApiResult<Me> {
        val r = api.me()
        if (r is ApiResult.Ok) {
            _me.value = r.value
            if (session.kind.value == AuthKind.Account) session.setName(displayNameOf(r.value))
        }
        return r
    }

    suspend fun updateDisplayName(name: String): ApiResult<Me> {
        val r = api.updateMe(UpdateMe(displayName = name.trim()))
        if (r is ApiResult.Ok) {
            _me.value = r.value
            session.setName(displayNameOf(r.value))
        }
        return r
    }

    /** Uploads [photo] and makes it my profile picture; null removes the picture. */
    suspend fun updateAvatar(photo: java.io.File?): ApiResult<Me> {
        val mediaId = if (photo == null) null else when (val up = api.uploadMedia(photo)) {
            is ApiResult.Ok -> up.value.id
            is ApiResult.Err -> return up
        }
        val r = api.setAvatar(mediaId)
        if (r is ApiResult.Ok) _me.value = r.value
        return r
    }

    /** The onboarding step: display name plus an optional handle, in one PATCH. */
    suspend fun saveProfile(displayName: String, handle: String?): ApiResult<Me> {
        val r = api.updateMe(UpdateMe(displayName = displayName.trim(), handle = handle?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }))
        if (r is ApiResult.Ok) {
            _me.value = r.value
            session.setName(displayNameOf(r.value))
        }
        return r
    }

    /**
     * Signs out on this device at once and tells the server in the background (which revokes the session
     * family, so the refresh token is useless afterwards).
     */
    fun logout() {
        val refresh = api.tokens.tokens?.refresh
        api.tokens.tokens = null
        _me.value = null
        session.signOut()
        if (refresh != null) scope.launch { api.logout(refresh) }
    }

    /** The refresh token was refused: the session is gone. Tokens are already cleared by the refresher. */
    fun sessionLost() {
        _me.value = null
        session.signOut()
    }

    private fun displayNameOf(me: Me) = me.displayName.ifBlank { me.handle }.ifBlank { "Driver" }
}
