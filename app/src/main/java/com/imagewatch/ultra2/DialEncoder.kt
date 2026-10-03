package com.imagewatch.ultra2

import java.util.Base64

class DialEncoded(val file: ByteArray, val preview: IntArray, val colors: Int)

/**
 * Convierte una imagen 240x288 al archivo de esfera con la MISMA disposición que subió HiwatchPro:
 *   [3257 B de glifos copiados de la captura] + cabecera 12 B + paleta 250 x RGB565 LE + 69120 índices
 *   = 72889 bytes (mismo tamaño que la captura).
 *
 * DUDA SIN RESOLVER: no sé si el índice de píxel empieza en 0 o en 1 (en 1 el 0 sería "transparente").
 * Solución: la paleta se escribe con cada color DUPLICADO en posiciones contiguas (2m+1 y 2m+2) y los
 * píxeles usan el índice 2m+2. Así se ve igual con las dos lecturas. Coste: máximo 124 colores en vez de 250.
 */
object DialEncoder {
    const val WIDTH = 240
    const val HEIGHT = 288
    const val PAL_ENTRIES = 250
    const val MAX_COLORS = 124
    const val GLYPH_BYTES = 3257

    private val HEADER = byteArrayOf(
        0x42, 0x4D, 0xF0.toByte(), 0x00, 0x20, 0x01, 0xFB.toByte(), 0x00, 0x00, 0x02, 0x00, 0x00
    )

    fun encode(argb: IntArray, w: Int, h: Int): DialEncoded {
        require(w == WIDTH && h == HEIGHT) { "La imagen debe ser ${WIDTH}x$HEIGHT" }
        val pal = medianCut(argb, MAX_COLORS)
        val idx = dither(argb, w, h, pal)
        val k = pal.size

        val pal565 = IntArray(PAL_ENTRIES)
        pal565[0] = rgb565(pal[0])
        for (m in 0 until k) {
            val v = rgb565(pal[m])
            pal565[2 * m + 1] = v
            pal565[2 * m + 2] = v
        }
        for (j in 2 * k + 1 until PAL_ENTRIES) pal565[j] = rgb565(pal[k - 1])

        val prefix = Base64.getDecoder().decode(DialPrefix.B64)
        val out = ByteArray(prefix.size + HEADER.size + PAL_ENTRIES * 2 + w * h)
        var o = 0
        System.arraycopy(prefix, 0, out, o, prefix.size); o += prefix.size
        System.arraycopy(HEADER, 0, out, o, HEADER.size); o += HEADER.size
        for (v in pal565) {
            out[o++] = (v and 0xFF).toByte()
            out[o++] = (v shr 8).toByte()
        }
        val preview = IntArray(w * h)
        for (i in 0 until w * h) {
            val m = idx[i].toInt() and 0xFF
            out[o++] = (2 * m + 2).toByte()
            preview[i] = 0xFF000000.toInt() or (pal[m] and 0xFFFFFF)
        }
        return DialEncoded(out, preview, k)
    }


    const val CLOCK_NORMAL = 0
    const val CLOCK_ZERO = 1
    const val CLOCK_RED = 2

    /**
     * Modos de prueba sobre los 28 registros de glifos (hora y fecha que el reloj dibuja sobre la foto):
     *  NORMAL: sin cambios.  ZERO: bits de cada glifo a cero.  RED: color de primer plano 0xF800 (diagnóstico:
     *  si la hora/fecha salen rojas, el reloj usa los glifos del archivo; si siguen blancas, no).
     * Si la estructura no es la esperada devuelve el archivo sin tocar.
     */
    fun styleClock(file: ByteArray, mode: Int): ByteArray {
        if (mode == CLOCK_NORMAL) return file
        val out = file.copyOf()
        var p = 0
        var n = 0
        while (p < GLYPH_BYTES) {
            if (p + 14 > out.size || out[p] != 0x58.toByte() || out[p + 1] != 0x63.toByte()) return file
            val w = (out[p + 2].toInt() and 0xFF) or ((out[p + 3].toInt() and 0xFF) shl 8)
            val h = (out[p + 4].toInt() and 0xFF) or ((out[p + 5].toInt() and 0xFF) shl 8)
            val len = 14 + ((w + 7) / 8) * h
            if (len <= 14 || p + len > GLYPH_BYTES) return file
            if (mode == CLOCK_ZERO) {
                for (i in p + 14 until p + len) out[i] = 0
            } else if (mode == CLOCK_RED) {
                out[p + 10] = 0x00
                out[p + 11] = 0xF8.toByte()
            }
            p += len
            n++
        }
        return if (p == GLYPH_BYTES && n == 28) out else file
    }

    fun withoutClock(file: ByteArray): ByteArray = styleClock(file, CLOCK_ZERO)

    private fun rgb565(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
    }

    /** Corte por la mediana. Usa un píxel de cada dos para ir más rápido en el teléfono. */
    private fun medianCut(argb: IntArray, k: Int): IntArray {
        val sample = IntArray((argb.size + 1) / 2) { argb[it * 2] and 0xFFFFFF }
        val boxes = ArrayList<IntArray>()
        boxes.add(sample)
        val shifts = intArrayOf(16, 8, 0)
        while (boxes.size < k) {
            var bi = -1
            var bestRange = 0
            var bestShift = 16
            for (i in boxes.indices) {
                val b = boxes[i]
                if (b.size < 2) continue
                for (sh in shifts) {
                    var mn = 255
                    var mx = 0
                    for (p in b) {
                        val v = (p shr sh) and 0xFF
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                    }
                    if (mx - mn > bestRange) {
                        bestRange = mx - mn
                        bi = i
                        bestShift = sh
                    }
                }
            }
            if (bi < 0) break
            val b = boxes.removeAt(bi)
            val sorted = b.sortedBy { (it shr bestShift) and 0xFF }
            val mid = sorted.size / 2
            boxes.add(sorted.subList(0, mid).toIntArray())
            boxes.add(sorted.subList(mid, sorted.size).toIntArray())
        }
        return IntArray(boxes.size) { i ->
            val b = boxes[i]
            var r = 0L
            var g = 0L
            var bl = 0L
            for (p in b) {
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                bl += p and 0xFF
            }
            val n = b.size.toLong()
            (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or ((bl / n).toInt())
        }
    }

    /** Floyd-Steinberg contra la paleta. Devuelve el índice de paleta (0..k-1) de cada píxel. */
    private fun dither(src: IntArray, w: Int, h: Int, pal: IntArray): ByteArray {
        val n = pal.size
        val pr = IntArray(n) { (pal[it] shr 16) and 0xFF }
        val pg = IntArray(n) { (pal[it] shr 8) and 0xFF }
        val pb = IntArray(n) { pal[it] and 0xFF }
        val er = FloatArray(w * h)
        val eg = FloatArray(w * h)
        val eb = FloatArray(w * h)
        val idx = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = src[i]
                val r = clamp(((p shr 16) and 0xFF) + er[i])
                val g = clamp(((p shr 8) and 0xFF) + eg[i])
                val b = clamp((p and 0xFF) + eb[i])
                var best = 0
                var bd = Int.MAX_VALUE
                for (j in 0 until n) {
                    val dr = r - pr[j]
                    val dg = g - pg[j]
                    val db = b - pb[j]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) {
                        bd = d
                        best = j
                    }
                }
                idx[i] = best.toByte()
                val rr = (r - pr[best]).toFloat()
                val gg = (g - pg[best]).toFloat()
                val bb = (b - pb[best]).toFloat()
                if (x + 1 < w) {
                    er[i + 1] += rr * 7f / 16f; eg[i + 1] += gg * 7f / 16f; eb[i + 1] += bb * 7f / 16f
                }
                if (y + 1 < h) {
                    if (x > 0) {
                        er[i + w - 1] += rr * 3f / 16f; eg[i + w - 1] += gg * 3f / 16f; eb[i + w - 1] += bb * 3f / 16f
                    }
                    er[i + w] += rr * 5f / 16f; eg[i + w] += gg * 5f / 16f; eb[i + w] += bb * 5f / 16f
                    if (x + 1 < w) {
                        er[i + w + 1] += rr / 16f; eg[i + w + 1] += gg / 16f; eb[i + w + 1] += bb / 16f
                    }
                }
            }
        }
        return idx
    }

    private fun clamp(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else (v + 0.5f).toInt()
}
