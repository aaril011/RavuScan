package com.project.ravuscan.ui.riwayat

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.project.ravuscan.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TANGGAL_ID = Locale("in", "ID")

fun formatTanggal(waktu: Long): String =
    SimpleDateFormat("d MMM yyyy • HH.mm", TANGGAL_ID).format(Date(waktu))

/** Waktu relatif: detik → menit → jam → hari → minggu → bulan → tahun. */
fun formatRelatif(waktu: Long): String {
    val diff = ((System.currentTimeMillis() - waktu) / 1000).coerceAtLeast(0)
    val menit = diff / 60
    val jam = menit / 60
    val hari = jam / 24
    return when {
        diff < 60 -> "$diff detik lalu"
        menit < 60 -> "$menit menit lalu"
        jam < 24 -> "$jam jam lalu"
        hari < 7 -> "$hari hari lalu"
        hari < 30 -> "${hari / 7} minggu lalu"
        hari < 365 -> "${hari / 30} bulan lalu"
        else -> "${hari / 365} tahun lalu"
    }
}

fun formatSkor(confidence: Float): String =
    String.format(Locale.US, "%.1f %%", confidence)

/** Nama lama "Tidak Teridentifikasi" ditampilkan sebagai "Tidak Dikenali". */
fun displayName(nama: String): String =
    if (nama == "Tidak Teridentifikasi") "Tidak Dikenali" else nama

/** Dialog info bertema (ikon centang hijau + tombol OK hijau). */
fun Fragment.showInfoDialog(
    title: String,
    message: String,
    okText: String = "OK",
    onOk: (() -> Unit)? = null
) {
    if (!isAdded) return
    val dialog = Dialog(requireContext())
    dialog.setContentView(R.layout.dialog_info)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.findViewById<TextView>(R.id.tvInfoTitle)?.text = title
    dialog.findViewById<TextView>(R.id.tvInfoMessage)?.text = message
    dialog.findViewById<TextView>(R.id.btnInfoOk)?.text = okText
    dialog.findViewById<View>(R.id.btnInfoOk)?.setOnClickListener {
        dialog.dismiss()
        onOk?.invoke()
    }
    dialog.show()
}

/** Dialog konfirmasi bertema (putih, tombol batal outline hijau + ok merah). */
fun Fragment.showConfirmDialog(
    title: String,
    message: String,
    okText: String = "Hapus",
    onOk: () -> Unit
) {
    if (!isAdded) return
    val dialog = Dialog(requireContext())
    dialog.setContentView(R.layout.dialog_confirm)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.findViewById<TextView>(R.id.tvDialogTitle)?.text = title
    dialog.findViewById<TextView>(R.id.tvDialogMessage)?.text = message
    dialog.findViewById<TextView>(R.id.btnDialogOk)?.text = okText
    dialog.findViewById<View>(R.id.btnDialogCancel)?.setOnClickListener { dialog.dismiss() }
    dialog.findViewById<View>(R.id.btnDialogOk)?.setOnClickListener {
        dialog.dismiss()
        onOk()
    }
    dialog.show()
}

/** Pindah fragment dengan animasi halus yang sama di seluruh aplikasi. */
fun Fragment.openFragment(fragment: Fragment) {
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

/**
 * Mode gambar penuh: sembunyikan chrome (header pudar ke atas, sheet pudar
 * ke bawah) atau tampilkan kembali (header turun, sheet naik).
 */
fun animateChrome(header: View, sheet: View, hide: Boolean) {
    header.animate().cancel()
    sheet.animate().cancel()
    if (hide) {
        header.animate().alpha(0f).translationY(-header.height.toFloat())
            .setDuration(250).withEndAction { header.visibility = View.GONE }.start()
        sheet.animate().alpha(0f).translationY(sheet.height.toFloat())
            .setDuration(250).withEndAction { sheet.visibility = View.GONE }.start()
    } else {
        header.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        header.alpha = 0f
        header.translationY = -header.height.toFloat()
        sheet.alpha = 0f
        sheet.translationY = sheet.height.toFloat()
        header.animate().alpha(1f).translationY(0f).setDuration(280).start()
        sheet.animate().alpha(1f).translationY(0f).setDuration(280).start()
    }
}

/** Tampilkan/sembunyikan status bar + navigation bar sistem. */
fun Fragment.setSystemBarsVisible(visible: Boolean) {
    val window = activity?.window ?: return
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (visible) {
        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } else {
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Tahan gambar untuk zoom: setelah 260ms ditekan, gambar membesar 1.8x dari
 * titik sentuh + chrome disembunyikan. Saat dilepas, zoom dan chrome kembali.
 * Ketukan cepat diteruskan ke [onTap]. [onHoldEnd] dipanggil saat lepas
 * setelah menahan (untuk sinkronisasi state fullscreen).
 */
fun View.attachHoldZoom(
    header: View,
    sheet: View,
    setBars: (Boolean) -> Unit,
    onTap: () -> Unit,
    onHoldEnd: () -> Unit
) {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var down = false
    var holding = false
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    val holdTime = 260L
    val zoom = 1.8f
    val interpolator = android.view.animation.DecelerateInterpolator()
    val check = Runnable {
        if (!down) return@Runnable
        holding = true
        pivotX = downX
        pivotY = downY
        animate().cancel()
        animate().scaleX(zoom).scaleY(zoom).setDuration(200)
            .setInterpolator(interpolator).start()
        animateChrome(header, sheet, true)
        setBars(false)
    }
    fun maxPanX(): Float = (width * (scaleX - 1f) / 2f).coerceAtLeast(0f)
    fun maxPanY(): Float = (height * (scaleY - 1f) / 2f).coerceAtLeast(0f)
    setOnTouchListener { _, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                down = true
                holding = false
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                handler.postDelayed(check, holdTime)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (holding) {
                    // Geser gambar yang diperbesar, dijepit agar tepi tak bocor.
                    translationX = (translationX + (e.x - lastX)).coerceIn(-maxPanX(), maxPanX())
                    translationY = (translationY + (e.y - lastY)).coerceIn(-maxPanY(), maxPanY())
                } else {
                    downX = e.x
                    downY = e.y
                }
                lastX = e.x
                lastY = e.y
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(check)
                val wasHolding = holding
                down = false
                holding = false
                if (wasHolding) {
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f)
                        .translationX(0f).translationY(0f)
                        .setDuration(220)
                        .setInterpolator(interpolator).start()
                    animateChrome(header, sheet, false)
                    setBars(true)
                    onHoldEnd()
                } else if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    onTap()
                }
            }
        }
        true
    }
}
