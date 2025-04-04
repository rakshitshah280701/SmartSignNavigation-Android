package com.surendramaran.yolov8tflite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
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

class MainActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService
    private var detector: Detector? = null
    private var signDetector: SignDetector? = null
    private var walkwayDamageDetector: WalkwayDamageDetector? = null

    private var isFrontCamera = false
    private var isSignMode = true
    private var isImageCaptured = false
    private var capturedBitmap: Bitmap? = null
    private var detectionArray = JSONArray()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedImage(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

//        binding.uploadImageButton.visibility = View.GONE

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
            walkwayDamageDetector = WalkwayDamageDetector(this@MainActivity, this@MainActivity)
            signDetector = SignDetector(this@MainActivity, this@MainActivity)
            signDetector?.init()
            walkwayDamageDetector?.init()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()
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
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
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
            if (isImageCaptured) {
                clearCapturedImage()
                binding.uploadImageButton.visibility = View.GONE
            } else {
                captureOneFrame()
                binding.uploadImageButton.visibility = View.VISIBLE
            }
        }

        binding.uploadImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        }
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
//            capturedBitmap = bitmap
            val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            capturedBitmap = argbBitmap


            isImageCaptured = true

            runOnUiThread {
                binding.capture.text = "Clear Image"
                binding.uploadImageButton.visibility = View.VISIBLE
                binding.uploadedImageView.setImageBitmap(argbBitmap)
                binding.uploadedImageView.visibility = View.VISIBLE
                cameraProvider?.unbindAll()

            }

            detector?.detect(bitmap)
            if (isSignMode) signDetector?.detect() else walkwayDamageDetector?.detect()
        } catch (e: Exception) {
            Log.e("UPLOAD", "Failed to decode selected image", e)
        }
    }

    private fun clearCapturedImage() {
        isImageCaptured = false
        capturedBitmap = null

        // Clear overlays and text
        binding.overlay.clear()
        binding.overlay.invalidate()
        binding.ocrTextView.text = ""

        // Reset detection data
        detectionArray = JSONArray()

        // Reset capture button text
        binding.capture.text = getString(R.string.capture_image)

        // Hide uploaded image
        binding.uploadedImageView.setImageDrawable(null)
        binding.uploadedImageView.visibility = View.GONE

        // Optional: Re-enable overlay if hidden during upload
        binding.overlay.visibility = View.VISIBLE

        // Restart the camera preview
        startCamera()
    }


    private fun captureOneFrame() {
        imageAnalyzer?.clearAnalyzer()
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
//            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)

//            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)


            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f)
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            imageProxy.close()
            capturedBitmap = rotatedBitmap
            isImageCaptured = true

            runOnUiThread {
                binding.capture.text = "Clear Image"
//                cameraProvider?.unbind(preview)
                cameraProvider?.unbindAll()

            }

            detector?.detect(rotatedBitmap)
            if (isSignMode) signDetector?.detect() else walkwayDamageDetector?.detect()
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
                            binding.ocrTextView.text = ocrResult.toString().trim()
                            Log.d("JSON_OUTPUT", detectionArray.toString(2))
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

                val norm = normalizeBoundingBox(
                    (box.x1 * imageWidth).toInt(),
                    (box.y1 * imageHeight).toInt(),
                    (box.x2 * imageWidth).toInt(),
                    (box.y2 * imageHeight).toInt(),
                    imageWidth, imageHeight
                )

                val detectionObject = JSONObject().apply {
                    put("label", label)
                    put("ocr_text", text.replace("\n", " "))
                    put("normalized_bounding_box", JSONObject().apply {
                        put("x", "%.4f".format(norm[0]))
                        put("y", "%.4f".format(norm[1]))
                        put("width", "%.4f".format(norm[2]))
                        put("height", "%.4f".format(norm[3]))
                    })
                }

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
        return listOf(
            xMin.toFloat() / imageWidth,
            yMin.toFloat() / imageHeight,
            (xMax - xMin).toFloat() / imageWidth,
            (yMax - yMin).toFloat() / imageHeight
        )
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

    private fun speakDetectedLabel(label: String) {
        if (label.isNotEmpty()) tts.speak(label, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
            binding.ocrTextView.text = ""
            detectionArray = JSONArray()
        }
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
            if (allPermissionsGranted()) startCamera()
            else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
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
