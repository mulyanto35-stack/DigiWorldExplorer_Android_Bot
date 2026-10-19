package de.robinthor.digiworldexplorer.capture

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** Same Android display source for capture allocation and final input validation. */
object PhysicalDisplay {
    @Suppress("DEPRECATION")
    fun bounds(context: Context, legacy: Boolean = false): PixelRect {
        val manager = context.getSystemService(WindowManager::class.java) ?: return PixelRect(0, 0, 720, 1600)

        // Android 11 (API 30) ke atas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !legacy) {
            val b = manager.maximumWindowMetrics.bounds
            return PixelRect(b.left, b.top, b.right, b.bottom)
        }

        // DUKUNGAN KHUSUS ANDROID 9 & 10 (API 28 - 29):
        // Gunakan getMetrics() untuk mendapatkan area display aktif game (Window Bounds)
        // daripada getRealMetrics() yang mengambil fisik mentah termasuk area poni/bar.
        val display = manager.defaultDisplay
        val realMetrics = DisplayMetrics().also { display.getRealMetrics(it) }

        if (legacy) {
            return PixelRect(0, 0, realMetrics.widthPixels, realMetrics.heightPixels)
        }

        val activeMetrics = DisplayMetrics().also { display.getMetrics(it) }

        // Jika terdapat selisih antara realMetrics dan activeMetrics (karena notch / status bar)
        val xOffset = (realMetrics.widthPixels - activeMetrics.widthPixels) / 2
        val yOffset = realMetrics.heightPixels - activeMetrics.heightPixels

        // Mengembalikan bounds area aktif tempat game dirender
        return if (activeMetrics.widthPixels > 0 && activeMetrics.heightPixels > 0) {
            PixelRect(
                xOffset.coerceAtLeast(0),
                yOffset.coerceAtLeast(0),
                xOffset + activeMetrics.widthPixels,
                yOffset + activeMetrics.heightPixels
            )
        } else {
            PixelRect(0, 0, realMetrics.widthPixels, realMetrics.heightPixels)
        }
    }
}
