package com.project.ravuscan.ui.beranda

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.project.ravuscan.R
import com.project.ravuscan.data.local.ImageStore
import com.project.ravuscan.data.local.entity.RiwayatEntity
import com.project.ravuscan.data.ml.LeafClassifier
import com.project.ravuscan.data.repository.RavuscanRepository
import com.project.ravuscan.ui.identifikasi.IndentifikasiFragment
import com.project.ravuscan.ui.petunjuk.PetunjukFragment
import com.project.ravuscan.ui.riwayat.RiwayatDetailFragment
import com.project.ravuscan.ui.riwayat.RiwayatFragment
import com.project.ravuscan.ui.riwayat.formatSkor
import com.project.ravuscan.ui.riwayat.formatRelatif
import com.project.ravuscan.ui.tentang.TentangFragment

class BerandaFragment : Fragment() {

    private var tvJumlah: TextView? = null
    private var recentHeader: View? = null
    private var recentScroll: HorizontalScrollView? = null
    private var recentContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_beranda, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvJumlah = view.findViewById(R.id.tvJumlahTanaman)
        recentHeader = view.findViewById(R.id.recentHeader)
        recentScroll = view.findViewById(R.id.recentScroll)
        recentContainer = view.findViewById(R.id.recentContainer)

        view.findViewById<View>(R.id.btnPindai).setOnClickListener {
            openFragment(IndentifikasiFragment())
        }
        view.findViewById<View>(R.id.cardRiwayat).setOnClickListener {
            openFragment(RiwayatFragment())
        }
        view.findViewById<View>(R.id.cardCaraPakai).setOnClickListener {
            openFragment(PetunjukFragment())
        }
        view.findViewById<View>(R.id.tvLihatSemua).setOnClickListener {
            openFragment(RiwayatFragment())
        }
        val tvTentang = view.findViewById<TextView>(R.id.tvTentang)
        tvTentang.paintFlags = tvTentang.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvTentang.setOnClickListener {
            openFragment(TentangFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRiwayat()
    }

    private fun refreshRiwayat() {
        val repo = RavuscanRepository.get(requireContext())
        repo.count { n ->
            if (!isAdded) return@count
            tvJumlah?.text = "$n Tanaman"
        }
        repo.getLatest(3) { data ->
            if (!isAdded) return@getLatest
            recentHeader?.visibility = View.VISIBLE
            recentScroll?.visibility = View.VISIBLE
            recentContainer?.removeAllViews()
            if (data.isEmpty()) {
                recentContainer?.addView(buildEmptyCard())
            } else {
                data.forEach { recentContainer?.addView(buildRecentCard(it)) }
            }
        }
    }

    /** Placeholder dashed di tengah saat belum ada riwayat. Hanya tampilan. */
    private fun buildEmptyCard(): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val cardWidth = (ctx.resources.displayMetrics.widthPixels - (48 * d)).toInt()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.white))
            radius = 16 * d
            cardElevation = 0f
            strokeColor = ContextCompat.getColor(ctx, R.color.white)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dashed_box)
            val p = (24 * d).toInt()
            setPadding(p, p, p, p)
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
            setImageResource(R.drawable.icon_camera)
            setColorFilter(ContextCompat.getColor(ctx, R.color.ravu_green))
        }
        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
            typeface = resources.getFont(R.font.poppins_regular)
            includeFontPadding = false
            gravity = android.view.Gravity.CENTER
            setLineSpacing(2 * d, 1f)
            text = "Belum ada riwayat identifikasi.\nGunakan kamera untuk mulai."
            setTextColor(ContextCompat.getColor(ctx, R.color.ravu_grey))
            textSize = 13f
        }
        box.addView(icon)
        box.addView(text)
        card.addView(box)
        return card
    }

    private fun buildRecentCard(item: RiwayatEntity): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((148 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * d).toInt()
            }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.white))
            radius = 16 * d
            cardElevation = 1 * d
            strokeWidth = 0
            strokeColor = ContextCompat.getColor(ctx, R.color.white)
            isClickable = true
            isFocusable = true
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        val thumbCard = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (72 * d).toInt()
            )
            radius = 12 * d
            cardElevation = 0f
            strokeWidth = 0
        }
        val img = ImageView(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = item.namaTanaman
        }
        val bmp = ImageStore.decodeThumb(item.imagePath, 256)
        if (bmp != null) img.setImageBitmap(bmp) else img.setImageResource(R.drawable.logo_2)
        thumbCard.addView(img)
        val isUnknown = item.namaTanaman == "Tidak Teridentifikasi" || item.namaTanaman == "Tidak Dikenali"
        val lolos = item.confidence >= LeafClassifier.CONFIDENCE_THRESHOLD * 100
        val nama = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
            typeface = resources.getFont(R.font.poppins_semibold)
            includeFontPadding = false
            text = if (isUnknown) "Tidak Dikenali" else item.namaTanaman
            setTextColor(ContextCompat.getColor(ctx, R.color.ravu_navy))
            textSize = 13f
            maxLines = 1
        }
        val sub = TextView(ctx).apply {
            typeface = resources.getFont(R.font.poppins_regular)
            includeFontPadding = false
            text = "${formatSkor(item.confidence)} • ${formatRelatif(item.waktu)}"
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (lolos) R.color.ravu_green else R.color.warning_red
                )
            )
            textSize = 11f
            maxLines = 1
        }
        body.addView(thumbCard)
        body.addView(nama)
        body.addView(sub)
        card.addView(body)
        card.setOnClickListener { openFragment(RiwayatDetailFragment.newInstance(item.id)) }
        return card
    }

    private fun openFragment(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.frag_enter,
                R.anim.frag_exit,
                R.anim.frag_pop_enter,
                R.anim.frag_pop_exit
            )
            .replace(R.id.nav_host, fragment)
            .addToBackStack(null)
            .commit()
    }
}
