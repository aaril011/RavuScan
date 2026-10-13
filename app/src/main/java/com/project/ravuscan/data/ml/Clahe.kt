package com.project.ravuscan.data.ml

import android.graphics.Bitmap
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * CLAHE asli (Contrast Limited Adaptive Histogram Equalization).
 *
 * Cara kerja: citra dibagi ubin-ubin kecil (default 8x8), tiap ubin
 * diratakan histogramnya sendiri-sendiri sehingga kontras lokal (urat dan
 * tekstur daun) terangkat meski cahaya tidak merata. Agar noise tidak ikut
 * membesar, histogram tiap ubin DIPOTONG pada [clipLimit] dan kelebihannya
 * dibagi rata ke semua bin. Terakhir tiap piksel diinterpolasi bilinear dari
 * 4 ubin tetangga supaya tidak terlihat garis batas ubin.
 *
 * Diterapkan pada channel L (terang) ruang warna LAB agar warna RGB tidak
 * bergeser, lalu dikembalikan ke RGB.
 *
 * Catatan akurasi: parameter [clipLimit]/[tileGrid] harus sama dengan saat
 * training. Default 2.0/8x8 adalah nilai umum OpenCV; kalau hasil meleset,
 * beri tahu agar di-tune atau dimatikan via [LeafClassifier.USE_CLAHE].
 */
object Clahe {

    /** Terapkan CLAHE pada bitmap, kembalikan bitmap baru (ARGB_8888). */
    fun enhance(src: Bitmap, clipLimit: Float = 2.0f, tileGrid: Int = 8): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lArr = FloatArray(w * h)
        val aArr = FloatArray(w * h)
        val bArr = FloatArray(w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val lab = rgbToLab(
                (px shr 16) and 0xFF,
                (px shr 8) and 0xFF,
                px and 0xFF
            )
            lArr[i] = lab[0]
            aArr[i] = lab[1]
            bArr[i] = lab[2]
        }

        val lEq = claheGray(lArr, w, h, clipLimit, tileGrid)

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val rgb = labToRgb(lEq[i], aArr[i], bArr[i])
            out[i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** CLAHE pada satu channel 0-100 (L), kembalikan 0-100. */
    private fun claheGray(l: FloatArray, w: Int, h: Int, clipLimit: Float, tiles: Int): FloatArray {
        // Kerja dalam domain 0-255 seperti OpenCV.
        val gray = IntArray(l.size) { ((l[it] * 255f / 100f).toInt()).coerceIn(0, 255) }
        val tx = tiles.coerceIn(1, w)
        val ty = tiles.coerceIn(1, h)
        val tw = (w + tx - 1) / tx
        val th = (h + ty - 1) / ty
        val bins = 256

        // LUT tiap ubin.
        val luts = Array(tx * ty) { IntArray(bins) }
        for (jy in 0 until ty) {
            for (jx in 0 until tx) {
                val x0 = jx * tw
                val y0 = jy * th
                val x1 = minOf(x0 + tw, w)
                val y1 = minOf(y0 + th, h)
                val area = (x1 - x0) * (y1 - y0)
                if (area <= 0) continue
                val hist = IntArray(bins)
                for (y in y0 until y1) {
                    for (x in x0 until x1) hist[gray[y * w + x]]++
                }
                val limit = maxOf(1, (clipLimit * area / bins).toInt())
                var excess = 0
                for (b in 0 until bins) {
                    if (hist[b] > limit) {
                        excess += hist[b] - limit
                        hist[b] = limit
                    }
                }
                val inc = excess / bins
                var rest = excess % bins
                for (b in 0 until bins) {
                    hist[b] += inc
                    if (rest > 0) {
                        hist[b]++
                        rest--
                    }
                }
                var cdfMin = -1
                var cdf = 0
                val lut = luts[jy * tx + jx]
                for (b in 0 until bins) {
                    cdf += hist[b]
                    if (cdfMin < 0 && cdf > 0) cdfMin = cdf
                    lut[b] = if (cdfMin < 0 || area == cdfMin) {
                        b
                    } else {
                        ((cdf - cdfMin).toFloat() / (area - cdfMin) * 255f + 0.5f).toInt()
                            .coerceIn(0, 255)
                    }
                }
            }
        }

        // Interpolasi bilinear antar 4 ubin tetangga.
        val out = FloatArray(l.size)
        for (y in 0 until h) {
            val jy = ((y.toFloat() / h * ty) - 0.5f).toInt().coerceIn(0, ty - 1)
            val jy2 = minOf(jy + 1, ty - 1)
            val fy = ((y.toFloat() + 0.5f) * ty / h - jy - 0.5f).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val jx = ((x.toFloat() / w * tx) - 0.5f).toInt().coerceIn(0, tx - 1)
                val jx2 = minOf(jx + 1, tx - 1)
                val fx = ((x.toFloat() + 0.5f) * tx / w - jx - 0.5f).coerceIn(0f, 1f)
                val g = gray[y * w + x]
                val top = luts[jy * tx + jx][g] * (1f - fx) + luts[jy * tx + jx2][g] * fx
                val bot = luts[jy2 * tx + jx][g] * (1f - fx) + luts[jy2 * tx + jx2][g] * fx
                out[y * w + x] = (top * (1f - fy) + bot * fy) * 100f / 255f
            }
        }
        return out
    }

    // ---- RGB <-> LAB (D65) ----

    private fun rgbToLab(r8: Int, g8: Int, b8: Int): FloatArray {
        fun inv(v: Float): Float {
            val c = v / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = inv(r8.toFloat())
        val g = inv(g8.toFloat())
        val b = inv(b8.toFloat())
        val x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / 0.95047f
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / 1.08883f
        fun f(t: Float): Float =
            if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labToRgb(l: Float, a: Float, b: Float): IntArray {
        val lc = l.coerceIn(0f, 100f)
        val fy = (lc + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun finv(t: Float): Float {
            val t3 = t * t * t
            return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f
        }
        fun comp(t: Float): Int {
            val c = if (t <= 0.0031308f) 12.92f * t else 1.055f * t.pow(1f / 2.4f) - 0.055f
            return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        val x = finv(fx) * 0.95047f
        val y = finv(fy)
        val z = finv(fz) * 1.08883f
        return intArrayOf(
            comp(3.2404542f * x - 1.5371385f * y - 0.4985314f * z),
            comp(-0.9692660f * x + 1.8760108f * y + 0.0415560f * z),
            comp(0.0556434f * x - 0.2040259f * y + 1.0572252f * z)
        )
    }
}
