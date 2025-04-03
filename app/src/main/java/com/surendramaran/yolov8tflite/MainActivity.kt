package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener, OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false
    private var isDetectionActive = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private var walkwayDamageDetector: WalkwayDamageDetector? = null
    private var signDetector: SignDetector? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech

    private var isSignMode = true
    private var isImageCaptured = false
    private var capturedBitmap: Bitmap? = null

    private var detectionArray = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
            walkwayDamageDetector = WalkwayDamageDetector(this@MainActivity, this@MainActivity)
            signDetector = SignDetector(this@MainActivity, this@MainActivity)
            signDetector?.init()
            walkwayDamageDetector?.init()
        }

        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.isSignWalk.setOnClickListener {
            isSignMode = !isSignMode
            binding.isSignWalk.text = if (isSignMode) "Mode: Sidewalk" else "Mode: Sign"
            binding.modeStatus.text = if (isSignMode) "In: Sign Mode" else "In: Sidewalk Mode"
            if (isSignMode) {
                signDetector?.restart()
                signDetector?.create()
            } else {
                walkwayDamageDetector?.restart()
                walkwayDamageDetector?.create()
            }
        }

        binding.capture.setOnClickListener {
            if (isImageCaptured) clearCapturedImage() else captureOneFrame()
        }
    }

    private fun clearCapturedImage() {
        isImageCaptured = false
        capturedBitmap = null
        binding.overlay.clear()
        binding.overlay.invalidate()
        binding.ocrTextView.text = ""
        binding.capture.text = getString(R.string.capture_image)
        detectionArray = JSONArray()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun captureOneFrame() {
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            imageProxy.close()
            capturedBitmap = rotatedBitmap
            isImageCaptured = true
            imageAnalyzer?.clearAnalyzer()

            runOnUiThread {
                binding.capture.text = "Clear Image"
                cameraProvider?.unbind(preview)
            }

            if (isSignMode) signDetector?.detect() else walkwayDamageDetector?.detect()
            detector?.detect(rotatedBitmap)
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(boundingBoxes)
            binding.overlay.invalidate()

            if (boundingBoxes.isEmpty()) return@runOnUiThread

            val ocrResult = StringBuilder()
            val totalBoxes = boundingBoxes.size
            var completedCount = 0

            boundingBoxes.forEachIndexed { index, box ->
                val label = box.clsName
                val direction = getDirection(box)
                speakDetectedLabel("Detected a $label, $direction.")

                capturedBitmap?.let { bitmap ->
                    val w = bitmap.width
                    val h = bitmap.height

                    val x1 = (box.x1 * w).toInt()
                    val y1 = (box.y1 * h).toInt()
                    val x2 = (box.x2 * w).toInt()
                    val y2 = (box.y2 * h).toInt()

                    val cropWidth = (x2 - x1).coerceAtLeast(1)
                    val cropHeight = (y2 - y1).coerceAtLeast(1)
                    val croppedBitmap = Bitmap.createBitmap(bitmap, x1, y1, cropWidth, cropHeight)

                    runTextRecognition(croppedBitmap, label, ocrResult, index, box, w, h) {
                        completedCount++
                        if (completedCount == totalBoxes) {
                            runOnUiThread {
                                binding.ocrTextView.text = ocrResult.toString().trim()
                                Log.d("JSON_OUTPUT", detectionArray.toString(2))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun runTextRecognition(
        bitmap: Bitmap,
        label: String,
        resultBuilder: StringBuilder,
        index: Int,
        box: BoundingBox,
        imageWidth: Int,
        imageHeight: Int,
        onComplete: () -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim().takeIf { it.isNotEmpty() } ?: "N/A"
                resultBuilder.append("${index + 1}. $label - $text\n")

                val x1 = (box.x1 * imageWidth).toInt()
                val y1 = (box.y1 * imageHeight).toInt()
                val x2 = (box.x2 * imageWidth).toInt()
                val y2 = (box.y2 * imageHeight).toInt()

                val norm = normalizeBoundingBox(x1, y1, x2, y2, imageWidth, imageHeight)

                val detectionObject = JSONObject()
                detectionObject.put("label", label)
                detectionObject.put("ocr_text", text.replace("\n", " "))

                val pixelBox = JSONObject()
                pixelBox.put("x1", x1)
                pixelBox.put("y1", y1)
                pixelBox.put("x2", x2)
                pixelBox.put("y2", y2)

                val normBox = JSONObject()
                normBox.put("x", "%.4f".format(norm[0]))
                normBox.put("y", "%.4f".format(norm[1]))
                normBox.put("width", "%.4f".format(norm[2]))
                normBox.put("height", "%.4f".format(norm[3]))

                detectionObject.put("bounding_box_pixels", pixelBox)
                detectionObject.put("normalized_bounding_box", normBox)

                detectionArray.put(detectionObject)
                tts.speak("$label detected. Text is $text", TextToSpeech.QUEUE_ADD, null, null)
                onComplete()
            }
            .addOnFailureListener {
                Log.e("OCR", "Text recognition failed", it)
                resultBuilder.append("${index + 1}. $label - N/A\n")
                onComplete()
            }
    }

    private fun normalizeBoundingBox(xMin: Int, yMin: Int, xMax: Int, yMax: Int, imageWidth: Int, imageHeight: Int): List<Float> {
        return try {
            listOf(
                xMin.toFloat() / imageWidth,
                yMin.toFloat() / imageHeight,
                (xMax - xMin).toFloat() / imageWidth,
                (yMax - yMin).toFloat() / imageHeight
            )
        } catch (e: Exception) {
            listOf(0f, 0f, 0f, 0f)
        }
    }

    private fun getDirection(box: BoundingBox): String {
        val centerX = (box.x1 + box.x2) / 2
        val screenWidth = binding.viewFinder.width
        return when {
            centerX < screenWidth / 3 -> "on the left"
            centerX > 2 * screenWidth / 3 -> "on the right"
            else -> "on front"
        }
    }

    private fun speakDetectedLabel(labelAndDirection: String) {
        if (labelAndDirection.isNotEmpty()) {
            tts.speak(labelAndDirection, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread { binding.overlay.clear() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isImageCaptured) {
            if (allPermissionsGranted()) startCamera() else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) startCamera()
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
