package com.project.ravuscan.data.local.dao

import com.project.ravuscan.data.local.RavuscanDatabase
import com.project.ravuscan.data.local.entity.RiwayatEntity

/** Akses tabel riwayat. Panggil di luar main thread. */
class RiwayatDao internal constructor(private val db: RavuscanDatabase) {

    fun getAll(): List<RiwayatEntity> = db.getAll()

    fun getLatest(limit: Int): List<RiwayatEntity> = db.getLatest(limit)

    fun count(): Int = db.count()

    fun getById(id: Long): RiwayatEntity? = db.getById(id)

    fun insert(entity: RiwayatEntity): Long = db.insert(entity)

    fun deleteByIds(ids: List<Long>): Int = db.deleteByIds(ids)

    fun deleteAll(): Int = db.deleteAll()
}
