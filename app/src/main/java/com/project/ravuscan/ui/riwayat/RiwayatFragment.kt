package com.project.ravuscan.ui.riwayat

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.project.ravuscan.R
import com.project.ravuscan.data.repository.RavuscanRepository

class RiwayatFragment : Fragment() {

    private lateinit var adapter: RiwayatAdapter
    private lateinit var rv: RecyclerView
    private lateinit var layoutKosong: View
    private lateinit var barPilih: View
    private lateinit var tvJudul: TextView
    private lateinit var btnMore: ImageView
    private lateinit var btnHapusPilih: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_riwayat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvJudul = view.findViewById(R.id.tvJudul)
        btnMore = view.findViewById(R.id.btnMore)
        rv = view.findViewById(R.id.rvRiwayat)
        layoutKosong = view.findViewById(R.id.layoutKosong)
        barPilih = view.findViewById(R.id.barPilih)
        btnHapusPilih = view.findViewById(R.id.btnHapusPilih)

        adapter = RiwayatAdapter(
            onClick = { item ->
                if (adapter.selectionMode) {
                    adapter.toggleSelect(item.id)
                    refreshSelectionUi()
                } else {
                    openFragment(RiwayatDetailFragment.newInstance(item.id))
                }
            },
            onLongClick = {
                if (!adapter.selectionMode) {
                    adapter.setSelectionMode(true)
                    adapter.toggleSelect(it.id)
                    refreshSelectionUi()
                }
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            if (adapter.selectionMode) exitSelection() else parentFragmentManager.popBackStack()
        }
        btnMore.setOnClickListener { showMenu() }
        view.findViewById<View>(R.id.btnBatalPilih).setOnClickListener { exitSelection() }
        btnHapusPilih.setOnClickListener { confirmDeleteSelected() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (adapter.selectionMode) {
                        exitSelection()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        RavuscanRepository.get(requireContext()).getAll { data ->
            if (!isAdded) return@getAll
            adapter.submit(data)
            rv.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
            layoutKosong.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            refreshSelectionUi()
        }
    }

    private fun showMenu() {
        if (!isAdded) return
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_menu_riwayat)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.findViewById<View>(R.id.btnMenuBatal)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnMenuPilih)?.setOnClickListener {
            dialog.dismiss()
            if (adapter.itemCount > 0) {
                adapter.setSelectionMode(true)
                refreshSelectionUi()
            } else {
                showInfoDialog(
                    "Belum ada riwayat",
                    "Hasil identifikasi tanamanmu akan muncul di sini setelah kamu memindai."
                )
            }
        }
        dialog.findViewById<View>(R.id.btnMenuHapusSemua)?.setOnClickListener {
            dialog.dismiss()
            if (adapter.itemCount > 0) confirmDeleteAll() else {
                showInfoDialog(
                    "Belum ada riwayat",
                    "Tidak ada data yang bisa dihapus saat ini."
                )
            }
        }
        dialog.show()
    }

    private fun refreshSelectionUi() {
        val selecting = adapter.selectionMode
        barPilih.visibility = if (selecting) View.VISIBLE else View.GONE
        btnMore.visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        tvJudul.text = if (selecting) "${adapter.selectedIds.size} dipilih" else "Riwayat Indentifikasi"
        val btn = view?.findViewById<TextView>(R.id.btnHapusPilih)
        btn?.text = "Hapus (${adapter.selectedIds.size})"
    }

    private fun exitSelection() {
        adapter.setSelectionMode(false)
        refreshSelectionUi()
    }

    private fun confirmDeleteSelected() {
        val n = adapter.selectedIds.size
        if (n == 0) {
            showInfoDialog(
                "Belum ada yang dipilih",
                "Ketuk riwayat yang ingin dihapus terlebih dahulu."
            )
            return
        }
        showConfirmDialog(
            title = "Hapus $n riwayat?",
            message = "Riwayat yang dipilih akan dihapus permanen dan tidak bisa dikembalikan."
        ) {
            RavuscanRepository.get(requireContext()).deleteByIds(adapter.selectedIds.toList()) {
                if (!isAdded) return@deleteByIds
                exitSelection()
                loadData()
                showInfoDialog("Riwayat dihapus", "$n riwayat berhasil dihapus permanen.")
            }
        }
    }

    private fun confirmDeleteAll() {
        showConfirmDialog(
            title = "Hapus semua riwayat?",
            message = "Seluruh riwayat identifikasi akan dihapus permanen dan tidak bisa dikembalikan."
        ) {
            RavuscanRepository.get(requireContext()).deleteAll {
                if (!isAdded) return@deleteAll
                exitSelection()
                loadData()
                showInfoDialog("Riwayat dihapus", "Seluruh riwayat berhasil dihapus permanen.")
            }
        }
    }
}
