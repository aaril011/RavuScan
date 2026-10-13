package com.project.ravuscan.data.repository

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.project.ravuscan.data.local.ImageStore
import com.project.ravuscan.data.local.RavuscanDatabase
import com.project.ravuscan.data.local.entity.RiwayatEntity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Akses riwayat di thread background, hasil dikembalikan di main thread. */
class RavuscanRepository private constructor(context: Context) {

    private val dao = RavuscanDatabase.get(context).riwayatDao()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun insert(entity: RiwayatEntity, onDone: ((Long) -> Unit)? = null) {
        executor.execute {
            val id = dao.insert(entity)
            onDone?.let { cb -> main.post { cb(id) } }
        }
    }

    fun getAll(onResult: (List<RiwayatEntity>) -> Unit) {
        executor.execute {
            val data = dao.getAll()
            main.post { onResult(data) }
        }
    }

    fun getLatest(limit: Int, onResult: (List<RiwayatEntity>) -> Unit) {
        executor.execute {
            val data = dao.getLatest(limit)
            main.post { onResult(data) }
        }
    }

    fun count(onResult: (Int) -> Unit) {
        executor.execute {
            val n = dao.count()
            main.post { onResult(n) }
        }
    }

    fun getById(id: Long, onResult: (RiwayatEntity?) -> Unit) {
        executor.execute {
            val item = dao.getById(id)
            main.post { onResult(item) }
        }
    }

    fun deleteByIds(ids: List<Long>, onDone: (() -> Unit)? = null) {
        executor.execute {
            val items = ids.mapNotNull { dao.getById(it) }
            dao.deleteByIds(ids)
            items.forEach { ImageStore.deleteQuietly(it.imagePath) }
            onDone?.let { cb -> main.post { cb() } }
        }
    }

    fun deleteAll(onDone: (() -> Unit)? = null) {
        executor.execute {
            val items = dao.getAll()
            dao.deleteAll()
            items.forEach { ImageStore.deleteQuietly(it.imagePath) }
            onDone?.let { cb -> main.post { cb() } }
        }
    }

    companion object {
        @Volatile
        private var instance: RavuscanRepository? = null

        fun get(context: Context): RavuscanRepository =
            instance ?: synchronized(this) {
                instance ?: RavuscanRepository(context.applicationContext).also { instance = it }
            }

        /** Salin foto hasil pindaian ke penyimpanan internal (panggil di background). */
        fun copyPhoto(context: Context, uri: Uri): String? =
            ImageStore.saveCopy(context.applicationContext, uri)
    }
}
