package com.project.ravuscan.ui.riwayat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.project.ravuscan.R
import com.project.ravuscan.data.local.ImageStore
import com.project.ravuscan.data.local.entity.RiwayatEntity

class RiwayatAdapter(
    private val onClick: (RiwayatEntity) -> Unit,
    private val onLongClick: (RiwayatEntity) -> Unit
) : RecyclerView.Adapter<RiwayatAdapter.Holder>() {

    private val items = mutableListOf<RiwayatEntity>()
    val selectedIds = mutableSetOf<Long>()

    var selectionMode: Boolean = false
        private set

    fun submit(data: List<RiwayatEntity>) {
        items.clear()
        items.addAll(data)
        selectedIds.retainAll(data.map { it.id }.toSet())
        if (selectedIds.isEmpty()) selectionMode = false
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun toggleSelect(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) selectionMode = false
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_riwayat, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view as MaterialCardView
        private val img: ImageView = view.findViewById(R.id.imgThumb)
        private val checked: ImageView = view.findViewById(R.id.imgSelected)
        private val chevron: ImageView = view.findViewById(R.id.imgChevron)
        private val nama: TextView = view.findViewById(R.id.tvNama)
        private val skor: TextView = view.findViewById(R.id.tvSkor)
        private val tanggal: TextView = view.findViewById(R.id.tvTanggal)

        fun bind(item: RiwayatEntity) {
            nama.text = displayName(item.namaTanaman)
            skor.text = formatSkor(item.confidence)
            tanggal.text = formatTanggal(item.waktu)
            val bmp = ImageStore.decodeThumb(item.imagePath, 256)
            if (bmp != null) {
                img.setImageBitmap(bmp)
            } else {
                img.setImageResource(R.drawable.logo_2)
            }
            val selected = selectedIds.contains(item.id)
            checked.visibility = if (selected) View.VISIBLE else View.GONE
            // Saat mode pilih: panah disembunyikan agar jelas ketukan = pilih, bukan buka detail.
            chevron.visibility = if (selectionMode) View.GONE else View.VISIBLE
            card.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(
                    itemView.context,
                    if (selected) R.color.ravu_mint else R.color.white
                )
            )
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }
}
