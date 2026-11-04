package app.convoyage.push

import app.convoyage.ConvoyageApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives UnifiedPush events from the distributor and hands them to [PushManager]. */
class ConvoyPushService : PushService() {
    private val push: PushManager get() = (application as ConvoyageApp).container.push

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        push.onNewEndpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        push.onMessage(message.content, message.decrypted)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        push.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(instance: String) {
        push.onUnregistered()
    }
}
