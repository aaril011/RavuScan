package com.project.ravuscan.ui.riwayat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.project.ravuscan.R
import com.project.ravuscan.data.local.ImageStore
import com.project.ravuscan.data.repository.RavuscanRepository

class RiwayatDetailFragment : Fragment() {

    private var itemId: Long = 0L
    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = arguments?.getLong(ARG_ID) ?: 0L
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_riwayat_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        // Foto: ketuk = mode gambar penuh, tahan = zoom + sembunyi panel.
        view.findViewById<View>(R.id.imgDetail).attachHoldZoom(
            view.findViewById(R.id.headerDetail),
            view.findViewById(R.id.sheetDetail),
            ::setSystemBarsVisible,
            { toggleFullscreen(view) },
            { isFullscreen = false }
        )
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreen) {
                        toggleFullscreen(requireView())
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
        loadData(view)
    }

    override fun onPause() {
        super.onPause()
        setSystemBarsVisible(true)
    }

    private fun toggleFullscreen(root: View) {
        val header = root.findViewById<View>(R.id.headerDetail) ?: return
        val sheet = root.findViewById<View>(R.id.sheetDetail) ?: return
        isFullscreen = !isFullscreen
        animateChrome(header, sheet, isFullscreen)
        setSystemBarsVisible(!isFullscreen)
    }

    private fun loadData(root: View) {
        RavuscanRepository.get(requireContext()).getById(itemId) { item ->
            if (!isAdded) return@getById
            if (item == null) {
                showInfoDialog("Riwayat tidak ditemukan", "Data ini mungkin sudah dihapus.") {
                    if (isAdded) parentFragmentManager.popBackStack()
                }
                return@getById
            }
            val confident = item.confidence >= 70f
            val pct = item.confidence.toInt().coerceIn(0, 100)
            root.findViewById<TextView>(R.id.tvNama)?.apply {
                text = displayName(item.namaTanaman)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        if (confident) R.color.ravu_navy else R.color.ravu_grey
                    )
                )
            }
            root.findViewById<TextView>(R.id.tvSkor)?.apply {
                text = String.format(
                    java.util.Locale.US,
                    if (confident) "%.1f%% cocok" else "%.1f%% tidak cocok",
                    item.confidence
                )
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        if (confident) R.color.ravu_green else R.color.warning_red
                    )
                )
            }
            root.findViewById<View>(R.id.pillSkor)?.setBackgroundResource(
                if (confident) R.drawable.bg_pill_mint else R.drawable.bg_pill_warning
            )
            root.findViewById<ImageView>(R.id.imgSkor)?.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (confident) R.color.ravu_green else R.color.warning_red
                )
            )
            root.findViewById<TextView>(R.id.tvBadge)?.apply {
                if (confident) {
                    text = "HASIL TERBAIK"
                    setBackgroundResource(R.drawable.bg_pill_mint)
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.ravu_green)
                    )
                } else {
                    text = "KEYAKINAN RENDAH"
                    setBackgroundResource(R.drawable.bg_pill_warning)
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.warning_red)
                    )
                }
            }
            root.findViewById<android.widget.ProgressBar>(R.id.progressSkor)?.apply {
                progress = pct
                val barColor = when {
                    pct > 70 -> R.color.ravu_green
                    pct == 70 -> R.color.warning_yellow
                    else -> R.color.warning_red
                }
                progressTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(requireContext(), barColor)
                )
            }
            root.findViewById<TextView>(R.id.tvTanggal)?.text = "Dipindai pada " + formatTanggal(item.waktu)
            val bmp = ImageStore.decodeThumb(item.imagePath, 1024)
            if (bmp != null) {
                root.findViewById<ImageView>(R.id.imgDetail)?.setImageBitmap(bmp)
            } else {
                root.findViewById<ImageView>(R.id.imgDetail)?.setImageResource(R.drawable.logo_2)
            }
            root.findViewById<View>(R.id.btnHapus)?.setOnClickListener {
                showConfirmDialog(
                    title = "Hapus riwayat ini?",
                    message = "\"${displayName(item.namaTanaman)}\" akan dihapus permanen dan tidak bisa dikembalikan."
                ) {
                    RavuscanRepository.get(requireContext()).deleteByIds(listOf(item.id)) {
                        if (!isAdded) return@deleteByIds
                        showInfoDialog("Riwayat dihapus", "\"${displayName(item.namaTanaman)}\" berhasil dihapus permanen.") {
                            if (isAdded) parentFragmentManager.popBackStack()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_ID = "riwayat_id"

        fun newInstance(id: Long) = RiwayatDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}
