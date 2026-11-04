package app.convoyage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.convoyage.data.DeepLink
import app.convoyage.data.Qr
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel

/** Opens the Android sharesheet with the invite: the app link and the https fallback. */
fun shareInvite(context: Context, code: String, name: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join my convoy on Convoyage")
        putExtra(Intent.EXTRA_TEXT, DeepLink.shareText(code, name))
    }
    context.startActivity(Intent.createChooser(send, "Invite to convoy").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Shares a live link: people without the app watch the convoy on a map in their browser. */
fun shareLiveLink(context: Context, url: String, name: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Follow ${name.ifBlank { "our convoy" }} live")
        putExtra(Intent.EXTRA_TEXT, "Follow ${name.ifBlank { "our convoy" }} live on the map (no app needed): $url")
    }
    context.startActivity(Intent.createChooser(send, "Share live link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copies the invite link and code to the clipboard. */
fun copyInvite(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Convoy invite", DeepLink.shareText(code)))
}

/** The invite link as a QR code, drawn dark-on-light with a quiet zone so any camera can read it. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val grid = remember(text) { Qr.modules(text) }
    val n = grid.size
    val quiet = 3
    Canvas(modifier.semantics { contentDescription = "QR code for the convoy invite" }) {
        val cell = size.minDimension / (n + 2 * quiet)
        drawRect(Color.White, Offset.Zero, size)
        val dark = Color(0xFF05080D)
        for (y in 0 until n) for (x in 0 until n) {
            if (grid[y][x]) drawRect(dark, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The invite QR with the code under it, plus Share and Copy. */
@Composable
fun InviteDialog(code: String, name: String, onCopied: () -> Unit, onDismiss: () -> Unit) {
    val c = Cv.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().cutPanel(c.surface100, c.lineStrong, CutMShape).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow("INVITE TO ${name.ifBlank { "CONVOY" }.uppercase()}")
            QrImage(DeepLink.joinUrl(code), Modifier.size(220.dp))
            CvText(code, Cv.type.displayL, c.signal, textAlign = TextAlign.Center)
            CvText("Scan with a camera, or open ${DeepLink.webUrl(code)}", Cv.type.bodyS, c.inkMuted, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvButton("Share", { shareInvite(context, code, name) }, Modifier.weight(1f), icon = CvIcons.Share)
                CvButton("Copy", { copyInvite(context, code); onCopied() }, Modifier.weight(1f), CvButtonKind.Ghost)
            }
            CvButton("Done", onDismiss, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
    }
}

/** A small chamfered confirm dialog. */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    val c = Cv.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().cutPanel(c.surface100, if (danger) c.pulse else c.lineStrong, CutMShape).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            CvText(title, Cv.type.title, c.ink)
            CvText(body, Cv.type.body, c.inkMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvButton("Cancel", onDismiss, Modifier.weight(1f), CvButtonKind.Ghost)
                CvButton(confirmLabel, { onConfirm(); onDismiss() }, Modifier.weight(1f), if (danger) CvButtonKind.Alert else CvButtonKind.Primary)
            }
        }
    }
}
