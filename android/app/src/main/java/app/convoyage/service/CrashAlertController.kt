package app.convoyage.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.convoyage.data.ConvoyRepository
import app.convoyage.data.CrashCountdown
import app.convoyage.data.LocationTracker
import app.convoyage.data.SessionStore
import app.convoyage.data.SosMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the full-screen crash alert shows. */
sealed interface CrashUi {
    data object Idle : CrashUi

    /** The "Are you OK?" countdown. */
    data class Counting(val remainingS: Int, val simulated: Boolean) : CrashUi

    /** The countdown ran out. [sentToConvoy] false = there was no convoy (or no connection) to tell. */
    data class Expired(val sentToConvoy: Boolean, val message: String) : CrashUi
}

/**
 * Runs the 30 second "Are you OK?" countdown after a detection. "I'm OK" cancels it. If it runs out, an SOS-tagged
 * message with our position goes to the convoy chat and a local alert is shown. It never calls emergency services.
 */
class CrashAlertController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val convoy: ConvoyRepository,
    private val tracker: LocationTracker,
    private val session: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow<CrashUi>(CrashUi.Idle)
    val ui: StateFlow<CrashUi> = _ui.asStateFlow()

    private val countdown = CrashCountdown()
    private var ticker: Job? = null

    /** Starts the countdown (ignored if one is already running). [simulated] marks the debug trigger. */
    fun trigger(simulated: Boolean = false) {
        if (countdown.phase == CrashCountdown.Phase.Counting) return
        countdown.reset()
        countdown.start(now())
        _ui.value = CrashUi.Counting(countdown.remainingSeconds(now()), simulated)
        vibrate(true)
        Notifications.crashPrompt(context)
        ticker?.cancel()
        ticker = scope.launch {
            while (countdown.phase == CrashCountdown.Phase.Counting) {
                delay(250)
                val t = now()
                if (countdown.tick(t)) {
                    expire()
                } else if (countdown.phase == CrashCountdown.Phase.Counting) {
                    _ui.value = CrashUi.Counting(countdown.remainingSeconds(t), simulated)
                }
            }
        }
    }

    /** "I'm OK". */
    fun imOk() {
        countdown.cancel()
        ticker?.cancel()
        vibrate(false)
        Notifications.cancelCrashPrompt(context)
        _ui.value = CrashUi.Idle
    }

    fun dismissResult() {
        if (_ui.value is CrashUi.Expired) _ui.value = CrashUi.Idle
    }

    private fun expire() {
        vibrate(false)
        Notifications.cancelCrashPrompt(context)
        val fix = tracker.fix.value ?: tracker.lastKnown()
        val name = session.name.value ?: "A rider"
        val sent = convoy.sendStatus("sos", SosMessage.note(name, fix?.lat, fix?.lng))
        val message = if (sent) "No answer. An SOS with your location was sent to the convoy. Emergency services were not called." else "No answer, and there is no convoy to tell. Emergency services were not called: call 000/911 yourself if you need help."
        Notifications.sosResult(context, message)
        _ui.value = CrashUi.Expired(sent, message)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(on: Boolean) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (on) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) else v.cancel()
        }
    }
}
