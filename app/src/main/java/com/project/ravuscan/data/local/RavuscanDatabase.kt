package com.project.ravuscan.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.project.ravuscan.data.local.dao.RiwayatDao
import com.project.ravuscan.data.local.entity.RiwayatEntity

/** Database lokal SQLite (tanpa annotation processor). Thread-safe via helper. */
class RavuscanDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE riwayat (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "namaTanaman TEXT NOT NULL, " +
                "confidence REAL NOT NULL, " +
                "waktu INTEGER NOT NULL, " +
                "imagePath TEXT)"
        )
        db.execSQL("CREATE INDEX index_riwayat_waktu ON riwayat (waktu DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: tidak ada migrasi.
    }

    fun riwayatDao(): RiwayatDao = RiwayatDao(this)

    internal fun insert(entity: RiwayatEntity): Long {
        val values = ContentValues().apply {
            put("namaTanaman", entity.namaTanaman)
            put("confidence", entity.confidence)
            put("waktu", entity.waktu)
            put("imagePath", entity.imagePath)
        }
        return writableDatabase.insert("riwayat", null, values)
    }

    internal fun getAll(): List<RiwayatEntity> =
        query("SELECT id, namaTanaman, confidence, waktu, imagePath FROM riwayat ORDER BY waktu DESC", null)

    internal fun getLatest(limit: Int): List<RiwayatEntity> =
        query(
            "SELECT id, namaTanaman, confidence, waktu, imagePath FROM riwayat ORDER BY waktu DESC LIMIT ?",
            arrayOf(limit.toString())
        )

    internal fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM riwayat", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    internal fun getById(id: Long): RiwayatEntity? =
        query(
            "SELECT id, namaTanaman, confidence, waktu, imagePath FROM riwayat WHERE id = ?",
            arrayOf(id.toString())
        ).firstOrNull()

    internal fun deleteByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        return writableDatabase.delete("riwayat", "id IN ($marks)", ids.map { it.toString() }.toTypedArray())
    }

    internal fun deleteAll(): Int =
        writableDatabase.delete("riwayat", null, null)

    private fun query(sql: String, args: Array<String>?): List<RiwayatEntity> {
        val out = mutableListOf<RiwayatEntity>()
        readableDatabase.rawQuery(sql, args).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iNama = c.getColumnIndexOrThrow("namaTanaman")
            val iConf = c.getColumnIndexOrThrow("confidence")
            val iWaktu = c.getColumnIndexOrThrow("waktu")
            val iPath = c.getColumnIndexOrThrow("imagePath")
            while (c.moveToNext()) {
                out.add(
                    RiwayatEntity(
                        id = c.getLong(iId),
                        namaTanaman = c.getString(iNama) ?: "-",
                        confidence = c.getFloat(iConf),
                        waktu = c.getLong(iWaktu),
                        imagePath = c.getString(iPath)
                    )
                )
            }
        }
        return out
    }

    companion object {
        private const val NAME = "ravuscan.db"
        private const val VERSION = 1

        @Volatile
        private var instance: RavuscanDatabase? = null

        fun get(context: Context): RavuscanDatabase =
            instance ?: synchronized(this) {
                instance ?: RavuscanDatabase(context).also { instance = it }
            }
    }
}
