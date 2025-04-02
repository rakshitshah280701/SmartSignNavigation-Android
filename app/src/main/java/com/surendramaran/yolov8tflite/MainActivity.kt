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
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
            walkwayDamageDetector = WalkwayDamageDetector(this@MainActivity, this@MainActivity)
            signDetector = SignDetector(this@MainActivity, this@MainActivity)

            // Initialize both subclasses
            signDetector?.init()
            walkwayDamageDetector?.init()
        }

        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
    }

    private fun bindListeners() {
        binding.isSignWalk.text = "Mode: Sidewalk"
        binding.modeStatus.text = "In: Sign Mode"
        binding.apply {
            isSignWalk.setOnClickListener {
                isSignMode = !isSignMode // Toggle mode
                binding.isSignWalk.text = if (isSignMode) {
                    "Mode: Sidewalk"
                } else {
                    "Mode: Sign"
                }

                // Status text: what mode you're in now
                binding.modeStatus.text = if (isSignMode) {
                    "In: Sign Mode"
                } else {
                    "In: Sidewalk Mode"
                }
                if(isSignMode) {
                    signDetector?.restart()
                    signDetector?.create()
                } else {
                    walkwayDamageDetector?.restart()
                    walkwayDamageDetector?.create()
                }
            }
        }

        binding.capture.setOnClickListener {
            if (isImageCaptured) {
                // Clear the current captured image
                clearCapturedImage()
            } else {
                // Capture a new image
                captureOneFrame()
            }
        }
    }

//    private fun clearCapturedImage() {
//        isImageCaptured = false
//        capturedBitmap = null
//
//        // Clear overlay
//        binding.overlay.clear()
//        binding.overlay.invalidate()
//
//        // Change button text to indicate the next action will be capturing
//        binding.capture.text = "Capture Image"
//
//        // Resume the camera preview
//        startCamera()
//    }

    private fun clearCapturedImage() {
        isImageCaptured = false
        capturedBitmap = null

        // Clear overlay
        binding.overlay.clear()
        binding.overlay.invalidate()

        // Clear OCR text
        binding.ocrTextView.text = ""

        // Change button text to indicate the next action will be capturing
        binding.capture.text = getString(R.string.capture_image)

        // Resume the camera preview
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
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun captureOneFrame() {
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)

            imageProxy.use {
                bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            }

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            imageProxy.close()

            // Store the captured bitmap
            capturedBitmap = rotatedBitmap
            isImageCaptured = true

            // Remove analyzer so only one frame is captured
            imageAnalyzer?.clearAnalyzer()

            // Use the correct detector
            runOnUiThread {
                // Change button text to indicate the next action will be clearing
                binding.capture.text = "Clear Image"

                // Pause camera preview by unbinding
                cameraProvider?.unbind(preview)
            }

            if (isSignMode) {
                signDetector?.detect()
            } else {
                walkwayDamageDetector?.detect()
            }

            detector?.detect(rotatedBitmap)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        // Shutdown TTS
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isImageCaptured) {
            if (allPermissionsGranted()){
                startCamera()
            } else {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        }
    }

    // Text-to-Speech Initialization
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = tts.setLanguage(Locale.US)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported or missing data")
            }
        } else {
            Log.e(TAG, "TextToSpeech Initialization failed")
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

//    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
//        runOnUiThread {
//            binding.inferenceTime.text = "${inferenceTime}ms"
//            binding.overlay.apply {
//                setResults(boundingBoxes)
//                invalidate()
//            }
//
//            // Get the label of the detected object
//            if (boundingBoxes.isNotEmpty()) {
//                boundingBoxes.forEach { boundingBox ->
//                    val detectedLabel = boundingBox.clsName
//                    val direction = getDirection(boundingBox)
//                    val speechText = "Detected a $detectedLabel, $direction."
//                    speakDetectedLabel(speechText)
//
//                    // 🧠 1. Convert normalized bounding box coordinates to actual pixel values
//                    capturedBitmap?.let { bitmap ->
//                        val bitmapWidth = bitmap.width
//                        val bitmapHeight = bitmap.height
//
//                        val left = (boundingBox.x1 * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
//                        val top = (boundingBox.y1 * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
//                        val right = (boundingBox.x2 * bitmapWidth).toInt().coerceIn(left + 1, bitmapWidth)
//                        val bottom = (boundingBox.y2 * bitmapHeight).toInt().coerceIn(top + 1, bitmapHeight)
//
//                        // 🧠 2. Crop the region from the captured image
//                        val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
//
//                        // 🧠 3. Send the cropped image to ML Kit for OCR
//                        runTextRecognition(croppedBitmap)
//                    }
//                }
//            }
//        }
//    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            if (boundingBoxes.isEmpty()) return@runOnUiThread

            val ocrResult = StringBuilder()
            val totalBoxes = boundingBoxes.size
            var completedCount = 0

            boundingBoxes.forEachIndexed { index, boundingBox ->
                val detectedLabel = boundingBox.clsName
                val direction = getDirection(boundingBox)
                val speechText = "Detected a $detectedLabel, $direction."
                speakDetectedLabel(speechText)

                capturedBitmap?.let { bitmap ->
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height

                    val left = (boundingBox.x1 * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
                    val top = (boundingBox.y1 * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
                    val right = (boundingBox.x2 * bitmapWidth).toInt().coerceIn(left + 1, bitmapWidth)
                    val bottom = (boundingBox.y2 * bitmapHeight).toInt().coerceIn(top + 1, bitmapHeight)

                    val cropWidth = right - left
                    val cropHeight = bottom - top
                    if (cropWidth <= 0 || cropHeight <= 0) {
                        completedCount++
                        if (completedCount == totalBoxes) {
                            binding.ocrTextView.text = ocrResult.toString().trim()
                        }
                        return@let
                    }

                    val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

                    runTextRecognition(
                        croppedBitmap,
                        detectedLabel,
                        ocrResult,
                        index
                    ) {
                        completedCount++
                        if (completedCount == totalBoxes) {
                            runOnUiThread {
                                binding.ocrTextView.text = ocrResult.toString().trim()
                            }
                        }
                    }
                }
            }
        }
    }


//    private fun runTextRecognition(bitmap: Bitmap) {
//        val image = InputImage.fromBitmap(bitmap, 0)
//        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//
//        recognizer.process(image)
//            .addOnSuccessListener { visionText ->
//                val text = visionText.text.trim()
//                Log.d("OCR", "Detected text: $text")
//
//                if (text.isNotEmpty()) {
//                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
//                    runOnUiThread {
//                        binding.ocrTextView.text = text
//                    }
//                }
//            }
//            .addOnFailureListener { e ->
//                Log.e("OCR", "Text recognition failed", e)
//            }
//    }


    private fun runTextRecognition(
        bitmap: Bitmap,
        label: String,
        resultBuilder: StringBuilder,
        index: Int,
        onComplete: () -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim().ifEmpty { "N/A" }

                resultBuilder.append("${index + 1}. $label - $text\n")

                // Optional TTS per detection
                tts.speak("$label detected. Text is $text", TextToSpeech.QUEUE_ADD, null, null)

                onComplete()
            }
            .addOnFailureListener { e ->
                Log.e("OCR", "Text recognition failed", e)
                resultBuilder.append("${index + 1}. $label - N/A\n")
                onComplete()
            }
    }


    // Method to calculate the direction of the detected object
    private fun getDirection(boundingBox: BoundingBox): String {
        val centerX = (boundingBox.x1+ boundingBox.x2) / 2
        val screenWidth = binding.viewFinder.width

        // Determine the position of the object relative to the camera
        return when {
            centerX < screenWidth / 3 -> "on the left"
            centerX > 2 * screenWidth / 3 -> "on the right"
            else -> "on front"
        }
    }

    // Method to speak the detected label and direction
    private fun speakDetectedLabel(labelAndDirection: String) {
        if (labelAndDirection.isNotEmpty()) {
            tts.speak(labelAndDirection, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}