package com.project.ravuscan.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Klasifier daun ResNet50 float32 (.tflite) dengan 20 kelas.
 *
 * Tipe tensor dibaca saat runtime dari ukuran byte per elemen
 * (4 = float32, 2 = float16, 1 = quantized) sehingga tetap adaptif.
 *
 * Preprocessing WAJIB sama dengan saat training, kalau tidak akurasi anjlok:
 * - Center-crop persegi -> resize sesuai input model (umumnya 224x224)
 * - Skala piksel ke [0, 1] (bagi 255)
 * - Normalisasi ImageNet: mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
 *
 * Jika model ternyata dilatih TANPA normalisasi ImageNet (hanya [0,1]),
 * ubah [USE_IMAGENET_NORM] menjadi false.
 */
class LeafClassifier(context: Context) {

    data class Prediction(
        val label: String,
        val confidence: Float,
        val index: Int
    )

    private val interpreter: Interpreter
    private val delegates = mutableListOf<Delegate>()
    val labels: List<String>
    private val inputH: Int
    private val inputW: Int
    private val inputBytesPerElem: Int
    private val outputBytesPerElem: Int
    private val outputSize: Int

    init {
        labels = loadLabels(context)
        check(labels.size == NUM_CLASSES) {
            "Label (${labels.size}) != NUM_CLASSES ($NUM_CLASSES)"
        }
        val modelBuf = loadModel(context)

        var interp: Interpreter? = null
        var gpuError: String? = null
        var nnapiError: String? = null
        var cpuError: String? = null

        // 1. GPU delegate (umumnya tercepat untuk model float16).
        // Tangkap Throwable (termasuk NoClassDefFoundError) agar kegagalan
        // delegate tidak pernah membunuh proses.
        // Catatan: XNNPACK dimatikan eksplisit karena ia gagal alokasi pada model ini.
        try {
            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                val gpu = GpuDelegate()
                try {
                    interp = Interpreter(
                        modelBuf,
                        Interpreter.Options().addDelegate(gpu).setUseXNNPACK(false)
                    )
                    delegates.add(gpu)
                    Log.i(TAG, "delegate=GPU")
                } catch (t: Throwable) {
                    runCatching { gpu.close() }
                    throw t
                }
            } else {
                gpuError = "GPU delegate tidak didukung HP ini"
                Log.w(TAG, gpuError!!)
            }
        } catch (t: Throwable) {
            gpuError = "${t.javaClass.simpleName}: ${shortMsg(t)}"
            Log.e(TAG, "GPU delegate gagal: $gpuError")
        }
        // 2. NNAPI delegate (mendukung operand FLOAT16 secara native, API 27+).
        if (interp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val nnapi = NnApiDelegate(
                    NnApiDelegate.Options().setAllowFp16(true)
                )
                try {
                    interp = Interpreter(
                        modelBuf,
                        Interpreter.Options().addDelegate(nnapi).setUseXNNPACK(false)
                    )
                    delegates.add(nnapi)
                    Log.i(TAG, "delegate=NNAPI")
                } catch (t: Throwable) {
                    runCatching { nnapi.close() }
                    throw t
                }
            } catch (t: Throwable) {
                nnapiError = "${t.javaClass.simpleName}: ${shortMsg(t)}"
                Log.e(TAG, "NNAPI delegate gagal: $nnapiError")
            }
        } else if (interp == null) {
            nnapiError = "butuh Android 8.1+"
        }
        // 3. CPU (XNNPACK dulu yang tercepat; polos sebagai cadangan).
        if (interp == null) {
            try {
                interp = Interpreter(
                    modelBuf,
                    Interpreter.Options().apply {
                        numThreads = 4
                        setUseXNNPACK(true)
                    }
                )
                Log.i(TAG, "delegate=CPU+XNNPACK")
            } catch (t: Throwable) {
                Log.w(TAG, "CPU+XNNPACK gagal, coba CPU polos", t)
                try {
                    interp = Interpreter(
                        modelBuf,
                        Interpreter.Options().apply {
                            numThreads = 4
                            setUseXNNPACK(false)
                        }
                    )
                    Log.i(TAG, "delegate=CPU")
                } catch (t2: Throwable) {
                    cpuError = "${t2.javaClass.simpleName}: ${shortMsg(t2)}"
                    Log.e(TAG, "CPU fallback gagal: $cpuError")
                }
            }
        }
        interpreter = interp
            ?: throw IllegalStateException(
                "Model tidak bisa dibuka. GPU: ${gpuError ?: "-"} | NNAPI: ${nnapiError ?: "-"} | CPU: ${cpuError ?: "-"}"
            )

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        inputH = inShape.getOrNull(1) ?: INPUT_SIZE
        inputW = inShape.getOrNull(2) ?: INPUT_SIZE
        inputBytesPerElem = inTensor.numBytes() / inTensor.numElements().coerceAtLeast(1)
        val outTensor = interpreter.getOutputTensor(0)
        outputSize = outTensor.numElements()
        outputBytesPerElem = outTensor.numBytes() / outputSize.coerceAtLeast(1)
        Log.i(TAG, "input shape=${inShape.contentToString()} bytesPerElem=$inputBytesPerElem")
        Log.i(TAG, "output elements=$outputSize bytesPerElem=$outputBytesPerElem labels=${labels.size}")
        check(outputSize == labels.size) {
            "Output ($outputSize) != jumlah label (${labels.size})"
        }
    }

    fun classify(bitmap: Bitmap): Prediction {
        val input: ByteBuffer = when (inputBytesPerElem) {
            4 -> preprocessFloat(bitmap)
            2 -> preprocessHalf(bitmap)
            1 -> preprocessQuantized(bitmap)
            else -> throw IllegalStateException("Ukuran input tak didukung: $inputBytesPerElem byte/elemen")
        }
        val probs: FloatArray = when (outputBytesPerElem) {
            4 -> {
                val output = Array(1) { FloatArray(outputSize) }
                interpreter.run(input, output)
                asProbabilities(output[0])
            }
            2 -> {
                val output = ByteBuffer.allocateDirect(outputSize * 2).order(ByteOrder.nativeOrder())
                interpreter.run(input, output)
                output.rewind()
                asProbabilities(FloatArray(outputSize) { halfToFloat(output.short) })
            }
            1 -> {
                val output = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder())
                interpreter.run(input, output)
                output.rewind()
                val params = interpreter.getOutputTensor(0).quantizationParams()
                asProbabilities(FloatArray(outputSize) { (output.get().toInt() - params.zeroPoint) * params.scale })
            }
            else -> throw IllegalStateException("Ukuran output tak didukung: $outputBytesPerElem byte/elemen")
        }
        var best = 0
        for (i in probs.indices) if (probs[i] > probs[best]) best = i
        return Prediction(labels[best], probs[best], best)
    }

    /** Jika output sudah berupa probabilitas (jumlah ~= 1), pakai langsung; jika logit, softmax. */
    private fun asProbabilities(values: FloatArray): FloatArray {
        var sum = 0f
        var inRange = true
        for (v in values) {
            sum += v
            if (v < -0.001f || v > 1.001f) inRange = false
        }
        if (inRange && sum > 0.99f && sum < 1.01f) return values
        return softmax(values)
    }

    /** Decode URI (kamera/galeri) dengan aman dari OOM + perbaiki orientasi EXIF. */
    fun decodeUri(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
            while (longest / sample > maxSide) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null
            val rotated = applyExifOrientation(context, uri, bmp)
            if (rotated != null && rotated !== bmp) {
                bmp.recycle()
                bmp = rotated
            }
            bmp
        }.getOrNull()
    }

    private fun resizedInput(src: Bitmap): Bitmap {
        // Center-crop persegi agar daun tidak gepeng saat resize.
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val resized = Bitmap.createScaledBitmap(cropped, inputW, inputH, true)
        if (cropped !== resized) cropped.recycle()
        return resized
    }

    private fun pixelsInput(src: Bitmap): IntArray {
        var resized = resizedInput(src)
        if (USE_CLAHE) {
            val enhanced = runCatching { Clahe.enhance(resized, CLAHE_CLIP, CLAHE_TILES) }.getOrNull()
            if (enhanced != null) {
                if (resized !== src && resized !== enhanced) resized.recycle()
                resized = enhanced
            }
        }
        val pixels = IntArray(inputW * inputH)
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        if (resized !== src) resized.recycle()
        return pixels
    }

    private fun normalizedPixel(px: Int, out: FloatArray, offset: Int) {
        val r = ((px shr 16) and 0xFF) / 255f
        val g = ((px shr 8) and 0xFF) / 255f
        val b = (px and 0xFF) / 255f
        if (USE_IMAGENET_NORM) {
            out[offset] = (r - MEAN_R) / STD_R
            out[offset + 1] = (g - MEAN_G) / STD_G
            out[offset + 2] = (b - MEAN_B) / STD_B
        } else {
            out[offset] = r
            out[offset + 1] = g
            out[offset + 2] = b
        }
    }

    private fun preprocessFloat(src: Bitmap): ByteBuffer {
        val pixels = pixelsInput(src)
        val buffer = ByteBuffer.allocateDirect(4 * pixels.size * 3).order(ByteOrder.nativeOrder())
        val tmp = FloatArray(3)
        for (px in pixels) {
            normalizedPixel(px, tmp, 0)
            buffer.putFloat(tmp[0])
            buffer.putFloat(tmp[1])
            buffer.putFloat(tmp[2])
        }
        buffer.rewind()
        return buffer
    }

    private fun preprocessHalf(src: Bitmap): ByteBuffer {
        val pixels = pixelsInput(src)
        val buffer = ByteBuffer.allocateDirect(2 * pixels.size * 3).order(ByteOrder.nativeOrder())
        val tmp = FloatArray(3)
        for (px in pixels) {
            normalizedPixel(px, tmp, 0)
            buffer.putShort(floatToHalfBits(tmp[0]))
            buffer.putShort(floatToHalfBits(tmp[1]))
            buffer.putShort(floatToHalfBits(tmp[2]))
        }
        buffer.rewind()
        return buffer
    }

    private fun preprocessQuantized(src: Bitmap): ByteBuffer {
        val pixels = pixelsInput(src)
        val params = interpreter.getInputTensor(0).quantizationParams()
        val buffer = ByteBuffer.allocateDirect(pixels.size * 3).order(ByteOrder.nativeOrder())
        for (px in pixels) {
            // Berlaku untuk uint8 (0..255) maupun int8: ambil 8 bit bawah hasil kuantisasi.
            val r = (((px shr 16) and 0xFF) / params.scale + params.zeroPoint).toInt().toByte()
            val g = ((((px shr 8) and 0xFF) / params.scale + params.zeroPoint).toInt().toByte())
            val b = (((px and 0xFF) / params.scale + params.zeroPoint).toInt().toByte())
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
        }
        buffer.rewind()
        return buffer
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        var sum = 0f
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = exp((logits[i] - max).toDouble()).toFloat()
            sum += out[i]
        }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bmp: Bitmap): Bitmap? {
        return runCatching {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                ?: return bmp
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }.getOrNull() ?: bmp
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.use { ch ->
                    return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }
    }

    private fun loadLabels(context: Context): List<String> {
        val json = context.assets.open(LABELS_FILE).bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("labels")
        return List(arr.length()) { arr.getString(it) }
    }

    fun close() {
        runCatching { interpreter.close() }
        delegates.toList().forEach { runCatching { it.close() } }
        delegates.clear()
    }

    companion object {
        private const val TAG = "LeafClassifier"
        const val MODEL_FILE = "resnet50_model_float32.tflite"
        const val LABELS_FILE = "Model.JSON"
        const val INPUT_SIZE = 224
        const val NUM_CLASSES = 20

        private fun shortMsg(t: Throwable): String {
            val m = t.message?.lineSequence()?.firstOrNull()?.take(220)
            return m ?: "-"
        }

        /** true = ImageNet mean/std (standar ResNet50). false = hanya [0,1]. */
        const val USE_IMAGENET_NORM = true

        /** true = terapkan CLAHE asli sebelum inferensi (bagian dari pendekatan RAVU). */
        const val USE_CLAHE = true
        const val CLAHE_CLIP = 2.0f
        const val CLAHE_TILES = 8

        /** Di bawah ambang ini hasil dianggap tidak teridentifikasi. */
        const val CONFIDENCE_THRESHOLD = 0.70f

        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f

        /** Konversi float32 -> half (16-bit) untuk input model FLOAT16. */
        private fun floatToHalfBits(f: Float): Short {
            val bits = java.lang.Float.floatToIntBits(f)
            val sign = (bits ushr 16) and 0x8000
            val exp = ((bits ushr 23) and 0xFF) - 112
            val mant = bits and 0x7FFFFF
            val half = when {
                exp <= 0 -> {
                    // Subnormal atau nol.
                    if (exp < -10) sign
                    else sign or ((mant or 0x800000) shr (1 - exp))
                }
                exp >= 31 -> sign or 0x7BFF // clamp ke max half (hindari Inf/NaN)
                else -> sign or (exp shl 10) or (mant shr 13)
            }
            return half.toShort()
        }

        /** Konversi half (16-bit) -> float32 untuk output model FLOAT16. */
        private fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            val fbits = when {
                exp == 0 -> {
                    if (mant == 0) {
                        sign
                    } else {
                        // Subnormal: normalisasi mantissa.
                        var m = mant
                        var e = -14
                        while ((m and 0x400) == 0) {
                            m = m shl 1
                            e--
                        }
                        m = m and 0x3FF
                        sign or ((e + 127) shl 23) or (m shl 13)
                    }
                }
                exp == 31 -> sign or 0x7F800000 or (mant shl 13)
                else -> sign or ((exp + 112) shl 23) or (mant shl 13)
            }
            return Float.fromBits(fbits)
        }
    }
}
