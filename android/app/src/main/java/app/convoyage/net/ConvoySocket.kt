package app.convoyage.net

import app.convoyage.protocol.ClientMessage
import app.convoyage.protocol.ProtocolJson
import app.convoyage.protocol.ServerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** What happened on one socket connection, in order. The flow completes after [Closed] or [Failed]. */
sealed interface SocketEvent {
    data object Opened : SocketEvent
    data class Received(val message: ServerMessage) : SocketEvent
    data class Closed(val code: Int, val reason: String) : SocketEvent

    /** [httpStatus] is set when the handshake was refused (e.g. 401 = dev auth is off). */
    data class Failed(val error: Throwable, val httpStatus: Int?) : SocketEvent
}

/** Close code the server uses when the session ended (no point retrying). */
const val CLOSE_SESSION_ENDED = 4401

/** `<base>/ws?dev_user=<id>&name=<name>`: the server's dev WebSocket auth (CONVOYAGE_DEV_AUTH=1). */
fun devSocketUrl(baseUrl: String, userId: String, name: String): HttpUrl? =
    baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()
        ?.addPathSegment("ws")
        ?.addQueryParameter("dev_user", userId)
        ?.addQueryParameter("name", name)
        ?.build()

/** Thin OkHttp WebSocket wrapper. One instance = at most one live connection at a time. */
class ConvoySocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    @Volatile
    private var ws: WebSocket? = null

    /** Opens a connection when collected and closes it when the collector goes away. */
    fun open(url: HttpUrl): Flow<SocketEvent> = callbackFlow {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySend(SocketEvent.Opened)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    ProtocolJson.decodeServer(text)?.let { trySend(SocketEvent.Received(it)) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    trySend(SocketEvent.Closed(code, reason))
                    close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trySend(SocketEvent.Closed(code, reason))
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    trySend(SocketEvent.Failed(t, response?.code))
                    close()
                }
            },
        )
        ws = socket
        awaitClose {
            if (ws === socket) ws = null
            socket.close(1000, null)
        }
    }

    /** Queues a message on the live connection. False if there is none. */
    fun send(message: ClientMessage): Boolean = ws?.send(ProtocolJson.encode(message)) ?: false
}
