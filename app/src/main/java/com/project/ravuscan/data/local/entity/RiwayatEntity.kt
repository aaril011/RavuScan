package com.project.ravuscan.data.local.entity

/** Satu baris riwayat identifikasi. Confidence disimpan 0-100. */
data class RiwayatEntity(
    val id: Long = 0,
    val namaTanaman: String,
    val confidence: Float,
    val waktu: Long,
    val imagePath: String? = null
)
