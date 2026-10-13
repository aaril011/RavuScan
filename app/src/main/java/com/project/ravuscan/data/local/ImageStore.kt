package com.project.ravuscan.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Salin foto riwayat ke penyimpanan internal + decode thumbnail hemat memori. */
object ImageStore {

    /** Salin isi [uri] ke filesDir/riwayat, kembalikan path absolut atau null. */
    fun saveCopy(context: Context, uri: Uri): String? {
        return runCatching {
            val dir = File(context.filesDir, "riwayat").also { it.mkdirs() }
            val out = File(dir, "scan_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            out.absolutePath
        }.getOrNull()
    }

    /** Decode [path] secukupnya untuk thumbnail selebar [maxSide] px. */
    fun decodeThumb(path: String?, maxSide: Int = 512): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** Hapus file foto milik satu baris riwayat (diabaikan bila gagal). */
    fun deleteQuietly(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).delete() }
    }
}
