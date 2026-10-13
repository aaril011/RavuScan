package com.project.ravuscan.ui.petunjuk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.project.ravuscan.R

class PetunjukFragment : Fragment() {

    private data class Tips(
        val salah: Int,
        val benar: Int,
        val caption: String
    )

    private val tips = listOf(
        Tips(
            R.drawable.fokus_salah,
            R.drawable.fokus_benar,
            "Fokuskan tanaman di tengah bingkai, hindari gambar gelap atau buram."
        ),
        Tips(
            R.drawable.ukuran_salah,
            R.drawable.ukuran_benar,
            "Jika tanaman terlalu besar untuk bingkai, pastikan untuk memfokuskan pada satu daun."
        ),
        Tips(
            R.drawable.jarak_salah,
            R.drawable.jarak_benar,
            "Hindari terlalu dekat, pastikan daun tanaman obat jernih dan lengkap."
        ),
        Tips(
            R.drawable.jumlah_salah,
            R.drawable.jumlah_benar,
            "Hanya termasuk satu spesies sekaligus dalam satu gambar."
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_petunjuk, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        val container = view.findViewById<LinearLayout>(R.id.containerTips)
        val inflater = LayoutInflater.from(requireContext())
        tips.forEach { tip ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            }
            row.addView(buildPhoto(inflater, row, tip.salah, "Salah", R.drawable.bg_pill_red))
            val gap = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), 1)
            }
            row.addView(gap)
            row.addView(buildPhoto(inflater, row, tip.benar, "Benar", R.drawable.bg_pill_green))
            container.addView(row)

            val caption = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(18)
                    bottomMargin = dp(8)
                }
                typeface = resources.getFont(R.font.poppins_regular)
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
                text = tip.caption
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ravu_navy))
                textSize = 14f
            }
            container.addView(caption)
        }

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(24) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ravu_mint))
        }
        container.addView(divider)

        val fiturTitle = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            typeface = resources.getFont(R.font.poppins_bold)
            includeFontPadding = false
            text = "Fitur kamera untuk mempermudah :"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.ravu_navy))
            textSize = 16f
        }
        container.addView(fiturTitle)

        val fitur = listOf(
            "Ketuk layar untuk mengunci fokus dan pencahayaan pada daun.",
            "Geser kiri atau kanan untuk menambah dan mengurangi cahaya (EV).",
            "Cubit dengan dua jari untuk zoom in dan zoom out.",
            "Ikon petir untuk flash: mati, nyala, dan otomatis."
        )
        fitur.forEach { text ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            val check = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    topMargin = dp(2)
                    marginEnd = dp(10)
                }
                setImageResource(R.drawable.ic_check_small)
            }
            val tv = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                typeface = resources.getFont(R.font.poppins_regular)
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
                this.text = text
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ravu_navy))
                textSize = 14f
            }
            row.addView(check)
            row.addView(tv)
            container.addView(row)
        }
    }

    private fun buildPhoto(
        inflater: LayoutInflater,
        parent: ViewGroup,
        res: Int,
        label: String,
        pill: Int
    ): View {
        val item = inflater.inflate(R.layout.item_petunjuk_foto, parent, false)
        item.findViewById<ImageView>(R.id.imgFoto)?.setImageResource(res)
        item.findViewById<TextView>(R.id.tvLabel)?.apply {
            text = label
            background = ContextCompat.getDrawable(requireContext(), pill)
        }
        return item
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        @JvmStatic
        fun newInstance() = PetunjukFragment()
    }
}
