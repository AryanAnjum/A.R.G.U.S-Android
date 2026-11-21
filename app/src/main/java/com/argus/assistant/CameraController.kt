package com.argus.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * The phone's own cameras (back or front): a live preview, upright JPEG frames for the server,
 * sharp photos for OCR, and QR code reading for pairing. start / stop / setFront run on the main thread.
 */
class CameraController(private val context: Context) {
    /** Called on the main thread when the camera turns on or off. */
    var onRunningChanged: ((Boolean) -> Unit)? = null
    /** Called on the camera thread with each frame. The frame is closed after the call returns. */
    var onFrame: ((ImageProxy) -> Unit)? = null
    /** Called on the main thread with the text of a QR code while [scanQR] is on. */
    var onQRCode: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isRunning = false
        private set
    var isFront = false
        private set
    @Volatile var scanQR = false

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var owner: LifecycleOwner? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var wanted = false
    private var lastQRScan = 0L
    private val qrReader = QRCodeReader()

    /** The screen that shows the preview. The camera pauses by itself when that screen is not visible. */
    fun attach(owner: LifecycleOwner) {
        this.owner = owner
        if (wanted) bind()
    }

    fun detach(owner: LifecycleOwner) {
        if (this.owner !== owner) return
        this.owner = null
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // nothing bound
        }
        preview = null
        capture = null
        setRunning(false)
    }

    fun setSurfaceProvider(surface: Preview.SurfaceProvider?) {
        surfaceProvider = surface
        preview?.setSurfaceProvider(surface)
    }

    fun start() {
        if (wanted && isRunning) return
        wanted = true
        bind()
    }

    fun stop() {
        wanted = false
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // nothing bound
        }
        preview = null
        capture = null
        setRunning(false)
    }

    fun setFront(front: Boolean) {
        if (front == isFront) return
        isFront = front
        if (wanted) bind()
    }

    private fun setRunning(on: Boolean) {
        if (on == isRunning) return
        isRunning = on
        onRunningChanged?.invoke(on)
    }

    private fun bind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                onError?.invoke("The camera isn't available on this phone.")
                return@addListener
            }
            provider = cameraProvider
            val lifecycleOwner = owner
            if (!wanted || lifecycleOwner == null) return@addListener

            val newPreview = Preview.Builder().build()
            newPreview.setSurfaceProvider(surfaceProvider)
            // Frames for the server: 720p is plenty for the 1024 px the model gets.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector(Size(1280, 720)))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (scanQR) readQR(image)
                    onFrame?.invoke(image)
                } catch (e: Exception) {
                    // a bad frame is skipped
                } finally {
                    image.close()
                }
            }
            // Photos for reading text: 1080p or better.
            val newCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(selector(Size(1920, 1080)))
                .build()
            val lens = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, lens, newPreview, analysis, newCapture)
                preview = newPreview
                capture = newCapture
                setRunning(true)
            } catch (e: Exception) {
                preview = null
                capture = null
                setRunning(false)
                onError?.invoke(
                    if (isFront) "This phone's front camera couldn't start." else "This phone's back camera couldn't start."
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun selector(size: Size): ResolutionSelector =
        ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

    /** Upright JPEG with its long side at most [maxPx], base64 encoded. */
    fun encode(image: ImageProxy, maxPx: Int, quality: Int): String? {
        val source = image.toBitmap()
        val upright = scaled(source, maxPx, image.imageInfo.rotationDegrees)
        val encoded = toBase64Jpeg(upright, quality)
        if (upright !== source) upright.recycle()
        source.recycle()
        return encoded
    }

    /** Takes a full-quality photo and returns it as an upright base64 JPEG (long side [maxPx]), or null. */
    fun capturePhoto(maxPx: Int, done: (String?) -> Unit) {
        val imageCapture = capture
        if (imageCapture == null || !isRunning) {
            done(null)
            return
        }
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val encoded = try {
                    encode(image, maxPx, 90)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                main.post { done(encoded) }
            }

            override fun onError(exception: ImageCaptureException) {
                main.post { done(null) }
            }
        })
    }

    /** Looks for a QR code in the frame, a few times a second. */
    private fun readQR(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastQRScan < 200) return
        lastQRScan = now
        val source = image.toBitmap()
        val small = scaled(source, 800, 0)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(small.width, small.height, pixels)))
        if (small !== source) small.recycle()
        source.recycle()
        val text = try {
            qrReader.decode(bitmap).text
        } catch (e: Exception) {
            null  // no QR code in this frame
        } finally {
            qrReader.reset()
        }
        if (!text.isNullOrEmpty()) main.post { onQRCode?.invoke(text) }
    }

    companion object {
        fun scaled(source: Bitmap, maxPx: Int, rotationDegrees: Int): Bitmap {
            val longSide = maxOf(source.width, source.height)
            val scale = if (longSide > maxPx) maxPx.toFloat() / longSide else 1f
            if (scale == 1f && rotationDegrees == 0) return source
            val matrix = Matrix()
            if (scale != 1f) matrix.postScale(scale, scale)
            if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

        fun toBase64Jpeg(bitmap: Bitmap, quality: Int): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
