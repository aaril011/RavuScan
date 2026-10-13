package com.project.ravuscan.ui.identifikasi

import android.Manifest
import android.app.Dialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.project.ravuscan.R
import com.project.ravuscan.data.local.entity.RiwayatEntity
import com.project.ravuscan.data.ml.LeafClassifier
import com.project.ravuscan.data.repository.RavuscanRepository
import com.project.ravuscan.ui.riwayat.animateChrome
import com.project.ravuscan.ui.riwayat.attachHoldZoom
import com.project.ravuscan.ui.riwayat.setSystemBarsVisible
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class IndentifikasiFragment : Fragment() {

    private var photoUri: Uri? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private lateinit var cameraExecutor: ExecutorService
    private var classifier: LeafClassifier? = null
    private var isInferring = false
    private var isFullscreenHasil = false

    private lateinit var layoutAmbil: View
    private lateinit var layoutValidasi: View
    private lateinit var layoutHasil: View
    private lateinit var layoutLoading: View
    private lateinit var previewView: PreviewView
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchLastX = 0f
    private var touchAccumDx = 0f
    private var touchMoved = false
    private var pinchMode = false
    private var lastPinchDist = 0f
    private var lastZoomShown = 1f

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(requireContext(), "Izin kamera ditolak", Toast.LENGTH_SHORT).show()
                pickGallery.launch("image/*")
            }
        }

    private val pickGallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onImageReady(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_indentifikasi, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        layoutAmbil = view.findViewById(R.id.layoutAmbil)
        layoutValidasi = view.findViewById(R.id.layoutValidasi)
        layoutHasil = view.findViewById(R.id.layoutHasil)
        layoutLoading = view.findViewById(R.id.layoutLoading)
        previewView = view.findViewById(R.id.previewView)

        savedInstanceState?.getString(KEY_URI)?.let {
            photoUri = Uri.parse(it)
        }

        // Tahap 1: shutter = jepret live, thumb = galeri, rotate = flip kamera.
        view.findViewById<View>(R.id.btnShutter).setOnClickListener { takePhoto() }
        view.findViewById<View>(R.id.btnGalleryThumb).setOnClickListener { pickGallery.launch("image/*") }
        view.findViewById<View>(R.id.btnGalleryRotate).setOnClickListener { flipCamera() }
        view.findViewById<View>(R.id.btnFlash).setOnClickListener { toggleFlash() }
        view.findViewById<View>(R.id.btnCloseAmbil).setOnClickListener { goBack() }
        updateFlashIcon(view)
        loadGalleryThumbnail(view)

        // Sentuh preview: ketuk = fokus + exposure di titik sentuh,
        // geser 1 jari kiri/kanan = cahaya, cubit 2 jari = zoom.
        previewView.setOnTouchListener { _, event ->
            if (layoutAmbil.visibility != View.VISIBLE) return@setOnTouchListener true
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchLastX = event.x
                    touchDownY = event.y
                    touchAccumDx = 0f
                    touchMoved = false
                    pinchMode = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        pinchMode = true
                        touchMoved = true
                        lastPinchDist = fingerDistance(event)
                        touchAccumDx = 0f
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (pinchMode && event.pointerCount >= 2) {
                        val dist = fingerDistance(event)
                        if (lastPinchDist > 0f && dist > 0f) {
                            adjustZoomBy(dist / lastPinchDist)
                        }
                        lastPinchDist = dist
                    } else if (!pinchMode) {
                        val dx = event.x - touchLastX
                        touchLastX = event.x
                        touchAccumDx += dx
                        if (kotlin.math.abs(event.x - touchDownX) > dp(12) ||
                            kotlin.math.abs(event.y - touchDownY) > dp(12)
                        ) {
                            touchMoved = true
                        }
                        val threshold = dp(24)
                        val steps = (touchAccumDx / threshold).toInt()
                        if (steps != 0) {
                            touchAccumDx -= steps * threshold
                            adjustExposureBy(steps)
                        }
                    }
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    pinchMode = false
                    touchLastX = event.x
                    touchAccumDx = 0f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!touchMoved && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        focusAt(event.x, event.y)
                    }
                    touchMoved = false
                    touchAccumDx = 0f
                    pinchMode = false
                }
            }
            true
        }

        // Tahap 2: validasi -> inferensi model asli.
        view.findViewById<View>(R.id.btnUlangi).setOnClickListener {
            showAmbil()
            startCamera()
        }
        view.findViewById<View>(R.id.btnPindai).setOnClickListener { runModel() }

        // Tahap 3: hasil.
        view.findViewById<View>(R.id.btnPindaiLagi).setOnClickListener {
            showAmbil()
            startCamera()
        }
        view.findViewById<View>(R.id.btnBackHasil).setOnClickListener { showExitConfirm() }
        // Foto hasil: ketuk = mode gambar penuh, tahan = zoom + sembunyi panel.
        view.findViewById<View>(R.id.imgHasil).attachHoldZoom(
            view.findViewById(R.id.headerHasil),
            view.findViewById(R.id.sheetHasil),
            ::setSystemBarsVisible,
            ::toggleFullscreenHasil,
            { isFullscreenHasil = false }
        )

        // Tombol back HP: fullscreen dulu, lalu dialog, lalu kamera.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        layoutHasil.visibility == View.VISIBLE && isFullscreenHasil -> {
                            toggleFullscreenHasil()
                        }
                        layoutHasil.visibility == View.VISIBLE -> showExitConfirm()
                        layoutValidasi.visibility == View.VISIBLE -> {
                            showAmbil()
                            startCamera()
                        }
                        else -> {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        showAmbil(animate = false)
        // Fade masuk halus agar tidak terasa kepotong saat buka identifikasi.
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (layoutAmbil.visibility == View.VISIBLE &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        setSystemBarsVisible(true)
        runCatching { camera?.cameraControl?.enableTorch(false) }
        camera = null
        runCatching {
            ProcessCameraProvider.getInstance(requireContext()).get().unbindAll()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching { classifier?.close() }
        classifier = null
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    private fun startCamera() {
        lastZoomShown = 1f
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageCapture)
                applyTorchState()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Kamera tidak tersedia", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val dir = File(requireContext().cacheDir, "images").also { it.mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            output,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val uri = result.savedUri ?: Uri.fromFile(file)
                    Handler(Looper.getMainLooper()).post { onImageReady(uri) }
                }

                override fun onError(e: ImageCaptureException) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(requireContext(), "Gagal mengambil foto", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun flipCamera() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        camera = null
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        // Berlaku untuk hasil jepretan.
        imageCapture?.flashMode = flashMode
        // Nyalakan LED terus-menerus saat mode ON agar preview terang.
        applyTorchState()
        updateFlashIcon(view)
        val icon = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        val label = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "Flash nyala"
            ImageCapture.FLASH_MODE_AUTO -> "Flash otomatis"
            else -> "Flash mati"
        }
        showFlashFeedback(icon, label)
    }

    /** Pill hijau-putih bertema sebagai ganti Toast saat mode flash berganti. */
    private fun showFlashFeedback(iconRes: Int, label: String) {
        val pill = view?.findViewById<View>(R.id.flashLabel) ?: return
        view?.findViewById<ImageView>(R.id.flashLabelIcon)?.setImageResource(iconRes)
        view?.findViewById<TextView>(R.id.flashLabelText)?.text = label
        pill.alpha = 1f
        pill.visibility = View.VISIBLE
        pill.animate().cancel()
        pill.animate()
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                pill.animate()
                    .alpha(0f)
                    .setStartDelay(900)
                    .setDuration(300)
                    .withEndAction { pill.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    /** Sinkronkan LED torch dengan flashMode. ON = torch nyala, selain itu mati. */
    private fun applyTorchState() {
        val cam = camera ?: return
        runCatching {
            val hasFlash = try {
                cam.cameraInfo.hasFlashUnit()
            } catch (_: Exception) {
                true
            }
            if (!hasFlash) {
                if (flashMode == ImageCapture.FLASH_MODE_ON) {
                    Toast.makeText(requireContext(), "Perangkat tidak mendukung flash", Toast.LENGTH_SHORT).show()
                }
                return
            }
            cam.cameraControl.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        }
    }

    /** Cubit buka = zoom in, cubit tutup = zoom out (dibatasi kemampuan HP). */
    private fun adjustZoomBy(scale: Float) {
        if (scale <= 0f || !scale.isFinite()) return
        val cam = camera ?: return
        runCatching {
            val zoomState = cam.cameraInfo.zoomState.value ?: return
            val target = (zoomState.zoomRatio * scale)
                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            cam.cameraControl.setZoomRatio(target)
            val direction = when {
                target > lastZoomShown + 0.01f -> "Zoom in"
                target < lastZoomShown - 0.01f -> "Zoom out"
                else -> null
            }
            lastZoomShown = target
            if (direction != null) {
                flashTouchLabel(
                    direction + String.format(java.util.Locale.US, " %.1fx", target)
                )
            }
        }
    }

    private fun fingerDistance(event: android.view.MotionEvent): Float {
        return runCatching {
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.getOrDefault(0f)
    }

    /** Geser kanan = tambah cahaya, geser kiri = kurangi cahaya. */
    private fun adjustExposureBy(steps: Int) {
        val cam = camera ?: return
        runCatching {
            val state = cam.cameraInfo.exposureState
            if (!state.isExposureCompensationSupported) return
            val range = state.exposureCompensationRange
            val current = state.exposureCompensationIndex
            val target = (current + steps).coerceIn(range.lower, range.upper)
            if (target == current) return
            cam.cameraControl.setExposureCompensationIndex(target)
            showExposureLabel(target, state.exposureCompensationStep.toFloat())
        }
    }

    private fun showExposureLabel(index: Int, step: Float) {
        val ev = index * step
        flashTouchLabel(String.format(java.util.Locale.US, "%+.1f EV", ev))
    }

    private fun flashTouchLabel(text: String) {
        val label = view?.findViewById<TextView>(R.id.exposureLabel) ?: return
        label.text = text
        label.alpha = 1f
        label.visibility = View.VISIBLE
        label.animate().cancel()
        label.animate()
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                label.animate()
                    .alpha(0f)
                    .setStartDelay(700)
                    .setDuration(300)
                    .withEndAction { label.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    /** Tap pada preview = kunci fokus + exposure (AF + AE) di titik sentuh. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        runCatching {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
            showFocusRing(x, y)
            cam.cameraControl.startFocusAndMetering(action)
        }
    }

    /** Animasi kotak hijau singkat di titik sentuh sebagai umpan balik. */
    private fun showFocusRing(x: Float, y: Float) {
        val ring = view?.findViewById<View>(R.id.focusRing) ?: return
        val half = dp(36)
        ring.x = (x - half).coerceAtLeast(0f)
        ring.y = (y - half).coerceAtLeast(0f)
        ring.alpha = 1f
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f
        ring.visibility = View.VISIBLE
        ring.animate().cancel()
        ring.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                ring.animate()
                    .alpha(0f)
                    .setStartDelay(500)
                    .setDuration(250)
                    .withEndAction { ring.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun updateFlashIcon(root: View? = view) {
        val icon = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        root?.findViewById<ImageView>(R.id.btnFlash)?.setImageResource(icon)
    }

    /** Tampilkan foto terakhir dari galeri sebagai thumb; fallback ke icon galeri. */
    private fun loadGalleryThumbnail(root: View? = view) {
        val thumb = root?.findViewById<ImageView>(R.id.btnGalleryThumb) ?: return
        runCatching {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            requireContext().contentResolver.query(
                collection, projection, null, null, sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = ContentUris.withAppendedId(collection, id)
                    thumb.setPadding(0, 0, 0, 0)
                    thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    thumb.setImageURI(uri)
                    return
                }
            }
            // Fallback: icon galeri bawaan.
            thumb.setPadding(dp(14), dp(14), dp(14), dp(14))
            thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            thumb.setImageResource(R.drawable.galeri)
        }.onFailure {
            thumb.setPadding(dp(14), dp(14), dp(14), dp(14))
            thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            thumb.setImageResource(R.drawable.galeri)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun onImageReady(uri: Uri) {
        photoUri = uri
        runCatching { camera?.cameraControl?.enableTorch(false) }
        camera = null
        runCatching {
            ProcessCameraProvider.getInstance(requireContext()).get().unbindAll()
        }
        view?.findViewById<ImageView>(R.id.imgValidasi)?.setImageURI(uri)
        view?.findViewById<ImageView>(R.id.btnGalleryThumb)?.let { thumb ->
            thumb.setPadding(0, 0, 0, 0)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.setImageURI(uri)
        }
        showValidasi()
    }

    /** Inferensi real ResNet50: 224x224 + normalisasi ImageNet, di background thread. */
    private fun runModel() {
        if (isInferring) return
        val uri = photoUri
        if (uri == null) {
            Toast.makeText(requireContext(), "Belum ada foto untuk dipindai", Toast.LENGTH_SHORT).show()
            return
        }
        isInferring = true
        setPindaiEnabled(false)
        layoutLoading.alpha = 0f
        layoutLoading.visibility = View.VISIBLE
        layoutLoading.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        cameraExecutor.execute {
            try {
                val clf = getClassifier()
                val bmp = clf.decodeUri(requireContext(), uri)
                if (bmp == null) {
                    postResultError("Gagal membaca gambar")
                    return@execute
                }
                val result = clf.classify(bmp)
                bmp.recycle()
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    hideLoading()
                    val pct = (result.confidence * 100).toInt().coerceIn(0, 100)
                    val confident = result.confidence >= LeafClassifier.CONFIDENCE_THRESHOLD
                    view?.findViewById<ImageView>(R.id.imgHasil)?.let { img ->
                        photoUri?.let { img.setImageURI(it) }
                    }
                    view?.findViewById<TextView>(R.id.tvNamaTanaman)?.apply {
                        text = if (confident) result.label else "Tidak Dikenali"
                        setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (confident) R.color.ravu_navy else R.color.ravu_grey
                            )
                        )
                    }
                    view?.findViewById<TextView>(R.id.tvAkurasi)?.apply {
                        text = String.format(
                            java.util.Locale.US,
                            if (confident) "%.1f%% cocok" else "%.1f%% tidak cocok",
                            result.confidence * 100
                        )
                        setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (confident) R.color.ravu_green else R.color.warning_red
                            )
                        )
                    }
                    view?.findViewById<View>(R.id.pillAkurasi)?.setBackgroundResource(
                        if (confident) R.drawable.bg_pill_mint else R.drawable.bg_pill_warning
                    )
                    view?.findViewById<ImageView>(R.id.imgAkurasi)?.setColorFilter(
                        ContextCompat.getColor(
                            requireContext(),
                            if (confident) R.color.ravu_green else R.color.warning_red
                        )
                    )
                    view?.findViewById<android.widget.ProgressBar>(R.id.progressAkurasi)?.apply {
                        progress = pct
                        val barColor = when {
                            pct > 70 -> R.color.ravu_green
                            pct == 70 -> R.color.warning_yellow
                            else -> R.color.warning_red
                        }
                        progressTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), barColor)
                        )
                    }
                    view?.findViewById<TextView>(R.id.tvHasilBadge)?.apply {
                        if (confident) {
                            text = "HASIL TERBAIK"
                            setBackgroundResource(R.drawable.bg_pill_mint)
                            setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.ravu_green)
                            )
                        } else {
                            text = "KEYAKINAN RENDAH"
                            setBackgroundResource(R.drawable.bg_pill_warning)
                            setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.warning_red)
                            )
                        }
                    }
                    view?.findViewById<TextView>(R.id.tvHasilHint)?.visibility =
                        if (confident) View.GONE else View.VISIBLE
                    // Simpan ke riwayat lokal (nama, confidence, waktu, foto).
                    val finishedLabel = if (confident) result.label else "Tidak Dikenali"
                    val finishedScore = result.confidence * 100
                    val finishedUri = photoUri
                    cameraExecutor.execute {
                        val path = finishedUri?.let {
                            RavuscanRepository.copyPhoto(requireContext(), it)
                        }
                        RavuscanRepository.get(requireContext()).insert(
                            RiwayatEntity(
                                namaTanaman = finishedLabel,
                                confidence = finishedScore,
                                waktu = System.currentTimeMillis(),
                                imagePath = path
                            )
                        )
                    }
                    showHasil()
                    isInferring = false
                    setPindaiEnabled(true)
                }
            } catch (t: Throwable) {
                android.util.Log.e("Indentifikasi", "Inferensi gagal", t)
                postResultError("Model gagal (${t.javaClass.simpleName}): ${t.message}")
            }
        }
    }

    private fun getClassifier(): LeafClassifier {
        classifier?.let { return it }
        return LeafClassifier(requireContext().applicationContext).also { classifier = it }
    }

    private fun postResultError(msg: String) {
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post
            hideLoading()
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            isInferring = false
            setPindaiEnabled(true)
        }
    }

    private fun hideLoading() {
        layoutLoading.animate()
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { layoutLoading.visibility = View.GONE }
            .start()
    }

    private fun setPindaiEnabled(enabled: Boolean) {
        view?.findViewById<View>(R.id.btnPindai)?.isEnabled = enabled
        view?.findViewById<View>(R.id.btnPindai)?.alpha = if (enabled) 1f else 0.6f
    }

    /**
     * Transisi halus profesional: crossfade cepat + scale sangat subtle,
     * tanpa geser besar agar tidak terasa seperti PowerPoint.
     */
    private val animInterpolator = DecelerateInterpolator()

    private fun showStage(target: View, animate: Boolean = true) {
        val isAlready = target.visibility == View.VISIBLE &&
            (if (target == layoutAmbil) layoutValidasi.visibility == View.GONE && layoutHasil.visibility == View.GONE
            else if (target == layoutValidasi) layoutAmbil.visibility == View.GONE && layoutHasil.visibility == View.GONE
            else layoutAmbil.visibility == View.GONE && layoutValidasi.visibility == View.GONE)
        if (isAlready) return
        layoutAmbil.visibility = if (target == layoutAmbil) View.VISIBLE else View.GONE
        layoutValidasi.visibility = if (target == layoutValidasi) View.VISIBLE else View.GONE
        layoutHasil.visibility = if (target == layoutHasil) View.VISIBLE else View.GONE
        layoutLoading.visibility = View.GONE
        if (animate) {
            target.alpha = 0f
            target.scaleX = 0.985f
            target.scaleY = 0.985f
            target.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(animInterpolator)
                .start()
        } else {
            target.alpha = 1f
            target.scaleX = 1f
            target.scaleY = 1f
        }
    }

    private fun showAmbil(animate: Boolean = true) = showStage(layoutAmbil, animate)

    private fun showValidasi() = showStage(layoutValidasi)

    private fun showHasil() {
        isFullscreenHasil = false
        setSystemBarsVisible(true)
        view?.findViewById<View>(R.id.headerHasil)?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationY = 0f
        }
        view?.findViewById<View>(R.id.sheetHasil)?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            translationY = 0f
        }
        showStage(layoutHasil)
    }

    /** Ketuk gambar hasil untuk sembunyi/tampilkan panel + system bar. */
    private fun toggleFullscreenHasil() {
        val root = view ?: return
        val header = root.findViewById<View>(R.id.headerHasil) ?: return
        val sheet = root.findViewById<View>(R.id.sheetHasil) ?: return
        isFullscreenHasil = !isFullscreenHasil
        animateChrome(header, sheet, isFullscreenHasil)
        setSystemBarsVisible(!isFullscreenHasil)
    }

    private fun showExitConfirm() {
        if (!isAdded) return
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_exit_confirm)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (WindowManager.LayoutParams.MATCH_PARENT),
                (WindowManager.LayoutParams.WRAP_CONTENT)
            )
        }
        // Animasi dialog halus: fade + scale kecil.
        dialog.window?.attributes?.windowAnimations = android.R.style.Animation_Dialog
        dialog.findViewById<View>(R.id.btnExitCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnExitConfirm)?.setOnClickListener {
            dialog.dismiss()
            goBack()
        }
        dialog.show()
    }

    private fun goBack() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val KEY_URI = "photo_uri"
    }
}
