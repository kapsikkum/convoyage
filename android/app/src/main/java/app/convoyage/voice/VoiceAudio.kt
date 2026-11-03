package app.convoyage.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Where the voice-chat audio goes, and what happens to everyone else's audio while it plays.
 *
 * LiveKit's own audio handler would switch the phone into call mode for as long as the room is connected, which pushes a
 * helmet headset's music onto the low-quality call profile for the whole ride. So the room is created with no audio
 * handler and this class does the routing only while somebody is talking:
 *  - audio focus `GAIN_TRANSIENT_MAY_DUCK`, so music and navigation prompts duck instead of stopping;
 *  - communication mode, routed to a Bluetooth headset (helmet comms) or wired headset if one is there, else the speaker.
 * When the talking stops it is all put back.
 */
class VoiceAudio(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var active = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var scoStarted = false

    /** Which kind of output is in use, for the UI and logs: "bluetooth", "wired", "speaker" or null while idle. */
    @Volatile
    var route: String? = null
        private set

    @Synchronized
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) engage() else release()
    }

    private fun engage() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus = req
        audio.requestAudioFocus(req)
        savedMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route = pickRoute()
    }

    private fun release() {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { audio.clearCommunicationDevice() }
        } else {
            if (scoStarted) runCatching { audio.stopBluetoothSco() }
            scoStarted = false
            audio.isBluetoothScoOn = false
            audio.isSpeakerphoneOn = false
        }
        audio.mode = savedMode
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        route = null
    }

    @Suppress("DEPRECATION")
    private fun pickRoute(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val bt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            if (bt != null && audio.setCommunicationDevice(bt)) return "bluetooth"
            val wired = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
            if (wired != null && audio.setCommunicationDevice(wired)) return "wired"
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
            return "speaker"
        }
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (audio.isBluetoothScoAvailableOffCall && outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
            scoStarted = true
            return "bluetooth"
        }
        if (outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) return "wired"
        audio.isSpeakerphoneOn = true
        return "speaker"
    }
}
