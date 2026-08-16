package com.identium.nfc.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.identium.nfc.R
import com.identium.nfc.data.BulkLog
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.NdefBuilder
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.util.qr.QrDecoder
import com.identium.nfc.util.qr.QrDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Scan to encode — read a QR code with the camera and write its contents
 * straight onto an NFC tag.
 *
 * Two workflows:
 *  - **Single**: scan one code, tap one tag, stop.
 *  - **Continuous**: scan → tap tag → automatically go back to scanning for
 *    the next code. Built for transferring a box of QR-labelled items onto
 *    NFC tags without touching the phone in between.
 *
 * QR decoding is done entirely in-app by [QrDetector] + [QrDecoder] — no
 * ML Kit, no Play Services, no network. Camera frames come from the platform
 * camera2 API.
 */
class ScanToEncodeActivity : BaseNfcActivity() {

    private enum class Phase { NEED_PERMISSION, SCANNING, ARMED, PAUSED }

    private lateinit var textureView: TextureView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var payloadView: TextView
    private lateinit var counterView: TextView
    private lateinit var continuousSwitch: MaterialSwitch
    private lateinit var lockSwitch: MaterialSwitch
    private lateinit var actionBtn: MaterialButton
    private lateinit var torchBtn: MaterialButton

    private var phase = Phase.NEED_PERMISSION
    private var lastPayload: String? = null
    private var sessionWritten = 0
    private var sessionFailed = 0
    private var torchOn = false

    // camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var previewSize = Size(1280, 720)
    /**
     * Analysis runs on its own, much smaller stream. Detection cost is linear
     * in pixel count, so feeding it a full-resolution preview frame is what
     * makes a scanner feel dead — ~640x480 in, halved again below, keeps a
     * frame under ~30ms.
     */
    private var analysisSize = Size(640, 480)
    private val decoding = AtomicBoolean(false)

    // Reused across frames — allocating these per frame produced megabytes of
    // garbage a second and stalled the pipeline on GC.
    private var lumaBuf: ByteArray? = null
    private var rowBuf: ByteArray? = null
    private var workBuf: ByteArray? = null

    private var frameCounter = 0
    private var framesThisSecond = 0
    private var lastFpsStamp = 0L
    @Volatile private var analysisFps = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            phase = Phase.SCANNING
            renderState()
            openCameraIfReady()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Camera permission needed")
                .setMessage("Scan to encode reads QR codes with the camera. Without the " +
                        "permission this screen can't work — you can still type data manually " +
                        "on the Write tab.")
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Scan to encode"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = true
        }
        root.addView(buildToolbar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── camera preview ──
        val previewFrame = android.widget.FrameLayout(this)
        textureView = TextureView(this)
        previewFrame.addView(textureView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Simple viewfinder guide so the operator knows where to aim.
        val reticle = View(this).apply { setBackgroundResource(R.drawable.bg_scan_reticle) }
        previewFrame.addView(reticle, android.widget.FrameLayout.LayoutParams(
            dp(220), dp(220), Gravity.CENTER))

        torchBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Torch"
            setOnClickListener { toggleTorch() }
        }
        previewFrame.addView(torchBtn, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(0, 0, dp(12), dp(12)) })

        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── status ──
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        statusTitle = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusDetail = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
        }
        payloadView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.brand_blue))
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        statusBox.addView(statusTitle)
        statusBox.addView(statusDetail)
        statusBox.addView(payloadView, lp().apply { topMargin = dp(8) })
        root.addView(statusBox, lp())

        // ── controls ──
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(12))
        }
        continuousSwitch = MaterialSwitch(this).apply {
            text = "Continuous — keep scanning after each tag"
            isChecked = true
        }
        lockSwitch = MaterialSwitch(this).apply {
            text = "Lock each tag after writing (permanent)"
            isChecked = false
        }
        controls.addView(continuousSwitch, lp())
        controls.addView(lockSwitch, lp())

        counterView = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        }
        controls.addView(counterView, lp())

        actionBtn = MaterialButton(this).apply {
            text = "Rescan"
            setOnClickListener { onActionPressed() }
        }
        controls.addView(actionBtn, lp().apply { topMargin = dp(8) })
        root.addView(controls, lp())

        setContentView(root)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCameraIfReady()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        phase = if (hasCameraPermission()) Phase.SCANNING else Phase.NEED_PERMISSION
        renderState()
        updateCounter()
        if (!hasCameraPermission()) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildToolbar(): View {
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            setBackgroundResource(R.drawable.bg_brand_header)
            title = "Scan to encode"
            setTitleTextColor(getColor(R.color.white))
            navigationIcon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                ?.also { it.setTint(getColor(R.color.white)) }
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        setSupportActionBar(toolbar)
        return toolbar
    }

    // ── lifecycle ──

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) openCameraIfReady()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("QrScan").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500) } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    // ── camera ──

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun openCameraIfReady() {
        if (!hasCameraPermission() || !textureView.isAvailable || cameraDevice != null) return
        startBackgroundThread()
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                toast("No camera found on this device"); return
            }
            cameraId = id

            val map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList().orEmpty()

            // Preview can be big and pretty — the user aims with it.
            previewSize = yuvSizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)

            // Analysis stream stays small, but must share the preview's aspect
            // ratio — otherwise the analysed field of view differs from what
            // the user is aiming with, and centred codes get cropped away.
            val previewAspect = previewSize.width.toFloat() / previewSize.height
            analysisSize = yuvSizes
                .filter { it.width in 320..1024 }
                .filter { abs(it.width.toFloat() / it.height - previewAspect) < 0.05f }
                .minByOrNull { abs(it.width * it.height - 640 * 480) }
                ?: yuvSizes
                    .filter { abs(it.width.toFloat() / it.height - previewAspect) < 0.05f }
                    .minByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes.minByOrNull { it.width.toLong() * it.height }
                ?: Size(640, 480)

            lumaBuf = null; rowBuf = null; workBuf = null

            imageReader = ImageReader.newInstance(
                analysisSize.width, analysisSize.height,
                android.graphics.ImageFormat.YUV_420_888, 2
            ).apply { setOnImageAvailableListener(onFrame, backgroundHandler) }

            @Suppress("MissingPermission")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSession()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    runOnUiThread { toast("Camera error $error") }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            toast("Could not open camera: ${e.message}")
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder!!.build(), null, backgroundHandler
                            )
                        } catch (_: Exception) {}
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread { toast("Camera configuration failed") }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            toast("Camera session error: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        torchOn = false
    }

    private fun toggleTorch() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        torchOn = !torchOn
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            torchBtn.text = if (torchOn) "Torch on" else "Torch"
        } catch (_: Exception) {}
    }

    // ── frame processing ──

    private val onFrame = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
        try {
            if (phase != Phase.SCANNING || !decoding.compareAndSet(false, true)) return@OnImageAvailableListener

            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer

            // Copy the Y plane honouring both strides. rowStride is usually
            // > width, and a few devices report pixelStride > 1 even for Y.
            val luma = lumaBuf ?: ByteArray(width * height).also { lumaBuf = it }
            val row = rowBuf?.takeIf { it.size >= rowStride }
                ?: ByteArray(rowStride).also { rowBuf = it }
            buffer.rewind()
            var offset = 0
            for (y in 0 until height) {
                val toRead = minOf(rowStride, buffer.remaining())
                if (toRead <= 0) break
                buffer.get(row, 0, toRead)
                if (pixelStride == 1) {
                    System.arraycopy(row, 0, luma, offset, minOf(width, toRead))
                } else {
                    var x = 0
                    var src = 0
                    while (x < width && src < toRead) {
                        luma[offset + x] = row[src]; x++; src += pixelStride
                    }
                }
                offset += width
            }

            // Two passes alternate so both framings work without doubling cost:
            //  - even frames: whole frame at half resolution (code fills the view)
            //  - odd frames : centre crop at full resolution (code is small/far)
            // On devices that only offer a small analysis stream, halving would
            // leave too few pixels per module, so use the frame as-is.
            val useHalf = width > 480
            val aW = if (useHalf) width / 2 else width
            val aH = if (useHalf) height / 2 else height
            val work = workBuf?.takeIf { it.size >= aW * aH }
                ?: ByteArray(aW * aH).also { workBuf = it }

            frameCounter++
            if (!useHalf) {
                System.arraycopy(luma, 0, work, 0, aW * aH)
            } else if (frameCounter % 2 == 0) {
                downsampleHalf(luma, width, height, work)
            } else {
                centreCrop(luma, width, height, work, aW, aH)
            }

            val text = try {
                val matrix = QrDetector.detectAndSample(work, aW, aH)
                QrDecoder.decode(matrix)
            } catch (_: Exception) {
                null            // no code in this frame — very common, keep scanning
            }

            // Cheap liveness signal so a stalled pipeline is obvious on screen.
            framesThisSecond++
            val now = System.currentTimeMillis()
            if (now - lastFpsStamp >= 1000) {
                analysisFps = framesThisSecond
                framesThisSecond = 0
                lastFpsStamp = now
                mainHandler.post { if (phase == Phase.SCANNING) renderState() }
            }

            if (text != null) {
                mainHandler.post { onQrDecoded(text) }
            }
            decoding.set(false)
        } catch (_: Exception) {
            decoding.set(false)
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    /** Box-average 2x2 into [out]. Halves the pixel count and smooths noise. */
    private fun downsampleHalf(src: ByteArray, w: Int, h: Int, out: ByteArray) {
        val ow = w / 2
        val oh = h / 2
        for (y in 0 until oh) {
            val r0 = (y * 2) * w
            val r1 = r0 + w
            val o = y * ow
            for (x in 0 until ow) {
                val i = x * 2
                val a = src[r0 + i].toInt() and 0xFF
                val b = src[r0 + i + 1].toInt() and 0xFF
                val c = src[r1 + i].toInt() and 0xFF
                val d = src[r1 + i + 1].toInt() and 0xFF
                out[o + x] = ((a + b + c + d) shr 2).toByte()
            }
        }
    }

    /** Copy the centre [cw]x[ch] region at full resolution. */
    private fun centreCrop(src: ByteArray, w: Int, h: Int, out: ByteArray, cw: Int, ch: Int) {
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        for (y in 0 until ch) {
            System.arraycopy(src, (top + y) * w + left, out, y * cw, cw)
        }
    }

    private fun onQrDecoded(text: String) {
        if (phase != Phase.SCANNING) return
        lastPayload = text
        phase = Phase.ARMED
        buzz(true)
        renderState()
        armTagWrite(text)
    }

    // ── NFC write ──

    private fun armTagWrite(payload: String) {
        val willLock = lockSwitch.isChecked
        runOnNextTapSilently(
            work = { tag ->
                val uid = HexUtil.toHex(tag.id, ":")
                // A QR usually holds a URL; anything else is written as text
                // so the payload survives the round trip unchanged.
                val record = if (looksLikeUri(payload)) NdefBuilder.url(payload)
                else NdefBuilder.text(payload)
                val msg = android.nfc.NdefMessage(arrayOf(record))
                val res = TagOperations.writeNdef(tag, msg, makeReadOnly = willLock)
                Triple(uid, res.success, res.message)
            },
            onResult = { (uid, ok, message) ->
                if (ok) {
                    sessionWritten++
                    buzz(true)
                } else {
                    sessionFailed++
                    buzz(false)
                }
                BulkLog.append(
                    ctx = this, uid = uid, url = payload,
                    locked = willLock && ok, success = ok,
                    error = if (ok) "" else message,
                    outcome = if (ok) BulkLog.Outcome.WRITTEN else BulkLog.Outcome.FAILED
                )
                History.record(
                    this, History.Action.WRITE, uid = uid,
                    tagType = if (willLock) "Scan→NFC (locked)" else "Scan→NFC",
                    summary = payload, success = ok
                )
                updateCounter()

                statusTitle.text = if (ok) "✓ Written to tag" else "✗ Write failed"
                statusTitle.setTextColor(getColor(if (ok) R.color.success else R.color.error))
                statusDetail.text = if (ok) "UID $uid" else message

                if (continuousSwitch.isChecked) {
                    // Straight back to scanning for the next label.
                    mainHandler.postDelayed({
                        if (!isFinishing) { lastPayload = null; phase = Phase.SCANNING; renderState() }
                    }, 900L)
                } else {
                    phase = Phase.PAUSED
                    mainHandler.postDelayed({ if (!isFinishing) renderState() }, 900L)
                }
            }
        )
    }

    private fun looksLikeUri(s: String): Boolean {
        val t = s.trim()
        return t.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.*")) ||
                t.startsWith("www.", ignoreCase = true)
    }

    // ── UI state ──

    private fun onActionPressed() {
        when (phase) {
            Phase.ARMED -> {                 // cancel the pending write
                cancelPending()
                lastPayload = null
                phase = Phase.SCANNING
                renderState()
            }
            Phase.PAUSED -> {
                lastPayload = null
                phase = Phase.SCANNING
                renderState()
            }
            Phase.SCANNING -> toast("Point the camera at a QR code")
            Phase.NEED_PERMISSION -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun renderState() {
        when (phase) {
            Phase.NEED_PERMISSION -> {
                statusTitle.text = "Camera permission needed"
                statusTitle.setTextColor(getColor(R.color.text_primary))
                statusDetail.text = "Grant access to scan QR codes."
                payloadView.visibility = View.GONE
                actionBtn.text = "Grant permission"
            }
            Phase.SCANNING -> {
                statusTitle.text = "Point at a QR code"
                statusTitle.setTextColor(getColor(R.color.brand_blue))
                statusDetail.text = "Hold the code inside the square. Detection is automatic." +
                        if (analysisFps > 0) "   ·   ${analysisFps} fps" else ""
                payloadView.visibility = View.GONE
                actionBtn.text = "Scanning…"
            }
            Phase.ARMED -> {
                statusTitle.text = "Now tap an NFC tag"
                statusTitle.setTextColor(getColor(R.color.brand_blue))
                statusDetail.text = if (lockSwitch.isChecked)
                    "The tag will be written and permanently locked."
                else "Hold a tag against the back of the phone."
                payloadView.visibility = View.VISIBLE
                payloadView.text = lastPayload
                actionBtn.text = "Cancel & rescan"
            }
            Phase.PAUSED -> {
                payloadView.visibility = View.VISIBLE
                payloadView.text = lastPayload
                actionBtn.text = "Scan next code"
            }
        }
    }

    private fun updateCounter() {
        counterView.text = "This session: $sessionWritten written" +
                (if (sessionFailed > 0) " · $sessionFailed failed" else "")
    }

    private fun buzz(success: Boolean) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(
                    if (success) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                    else VibrationEffect.createWaveform(longArrayOf(0, 80, 90, 80), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                if (success) vib.vibrate(60) else vib.vibrate(longArrayOf(0, 80, 90, 80), -1)
            }
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
