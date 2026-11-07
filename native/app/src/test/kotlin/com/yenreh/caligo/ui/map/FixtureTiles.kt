package com.yenreh.caligo.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiles drawn here instead of fetched: a paper ground, a grid of streets
 * and an avenue, in the light or the dark tiles' colours. Records every
 * tile asked for, as zoom/x/y.
 */
class FixtureTiles(
    private val dark: Boolean = false,
    override val tilePixels: Int = 256,
    override val inverted: Boolean = false,
    override val id: String = "fixture-${if (dark) "dark" else "light"}-$tilePixels",
) : MapTileSource {
    val asked: MutableList<String> = CopyOnWriteArrayList()
    private val drawn = ConcurrentHashMap<String, ByteArray>()

    override suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray {
        val key = "$zoom/$x/$y"
        asked.add(key)
        return drawn.getOrPut(key) { draw(x, y) }
    }

    private fun draw(x: Int, y: Int): ByteArray {
        val size = tilePixels
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF262626.toInt() else 0xFFF5F1E8.toInt())
        val block = Paint().apply { color = if (dark) 0xFF2E2E2E.toInt() else 0xFFE9E4D6.toInt() }
        val street = Paint().apply {
            color = if (dark) 0xFF3D3D3D.toInt() else 0xFFFFFFFF.toInt()
            strokeWidth = size / 40f
        }
        val avenue = Paint().apply {
            color = if (dark) 0xFF4A4A4A.toInt() else 0xFFF7D9A0.toInt()
            strokeWidth = size / 18f
        }
        val step = size / 4f
        for (i in 0..3) {
            for (j in 0..3) {
                if ((i + j + x + y) % 3 == 0) {
                    canvas.drawRect(i * step + 6, j * step + 6, (i + 1) * step - 6, (j + 1) * step - 6, block)
                }
            }
        }
        for (i in 0..4) {
            canvas.drawLine(i * step, 0f, i * step, size.toFloat(), street)
            canvas.drawLine(0f, i * step, size.toFloat(), i * step, street)
        }
        if ((x + y) % 2 == 0) canvas.drawLine(0f, size.toFloat(), size.toFloat(), 0f, avenue)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
