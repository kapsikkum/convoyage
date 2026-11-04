package app.convoyage.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.convoyage.render.Ps1Catalog
import app.convoyage.render.Ps1Spec
import app.convoyage.render.VehicleThumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: renders catalogue models to files/sheet.png for checking shapes without the UI.
 * adb shell am start -n app.convoyage/.debug.ModelSheetActivity --es ids "ford-focus-rs-mk3,bmw-m3-g80" --es yaws "-90,-32,32"
 * then: adb exec-out run-as app.convoyage cat files/sheet.png > sheet.png
 */
class ModelSheetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = intent.getStringExtra("ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: Ps1Catalog.ALL.filter { it.shape != null }.map { it.id }
        val yaws = intent.getStringExtra("yaws")?.split(',')?.map { it.trim().toFloat() } ?: listOf(-90f, -32f, 148f)
        val paint = intent.getStringExtra("paint") ?: "#c8202c"
        val w = intent.getIntExtra("w", 320); val h = intent.getIntExtra("h", 200)
        val specs = ids.mapNotNull { id ->
            Ps1Catalog.byId(id)?.let { Ps1Spec.of(it.body.kind.wire, paint, "metallic", "stock", 0, it.body.wire, it.id) }
                ?: app.convoyage.render.Ps1Body.entries.firstOrNull { it.wire == id }?.let { Ps1Spec.of(it.kind.wire, paint, "metallic", "stock", 0, it.wire, "generic") }
        }
        val thumbs = VehicleThumbs(this)
        CoroutineScope(Dispatchers.Main).launch {
            val bmp = thumbs.sheet(specs, yaws, w, h)
            if (bmp != null) File(filesDir, "sheet.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("ModelSheet", "sheet ${specs.size} x ${yaws.size} done=${bmp != null}")
            finish()
        }
    }
}
