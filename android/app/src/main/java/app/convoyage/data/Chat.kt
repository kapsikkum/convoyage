package app.convoyage.data

import app.convoyage.protocol.ConvoyMessage

/** Convoy chat helpers. Pure. */
object Chat {
    val QUICK_REPLIES = listOf("Fuel stop", "Pull over", "All good", "Slow down")

    /** Messages from other people newer than [readTs]. */
    fun unread(messages: List<ConvoyMessage>, meId: String?, readTs: Long): Int =
        messages.count { it.userId != meId && it.ts > readTs }

    fun latestTs(messages: List<ConvoyMessage>): Long = messages.maxOfOrNull { it.ts } ?: 0L

    /** The newest SOS from someone else that arrived after [readTs], or null. */
    fun pendingSos(messages: List<ConvoyMessage>, meId: String?, readTs: Long): ConvoyMessage? =
        messages.filter { it.userId != meId && it.ts > readTs && SosMessage.isSos(it.text) }.maxByOrNull { it.ts }
}
