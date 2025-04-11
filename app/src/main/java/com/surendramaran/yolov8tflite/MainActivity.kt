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
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
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
    private lateinit var uploadedImageView: BoundingBoxImageView

    private var detector: Detector? = null
    private var signDetector: SignDetector? = null
    private var walkwayDamageDetector: WalkwayDamageDetector? = null

    private var isFrontCamera = false
    private var isSignMode = true
    private var isImageCaptured = false
    private var isImageUploaded = false

    private var capturedBitmap: Bitmap? = null
    private var detectionArray = JSONArray()

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val chatGPTUrl = "https://api.openai.com/v1/chat/completions"
    private val apiKey = "Bearer-dummy-key" // Replace with your actual key

    // Registers a launcher for picking an image from the gallery
    private val pickImageLauncher = registerForActivityResult(
        // Use the contract that allows launching an external activity and getting a result back
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if the user selected an image successfully
        if (result.resultCode == Activity.RESULT_OK) {
            // Get the URI of the selected image from the result
            result.data?.data?.let { uri ->
                // Pass the image URI to the handler function for decoding and processing
                // uri reference to the image the user picked from their gallery
                handleSelectedImage(uri)
            }
        }
    }

    /**
     * Called when the activity is first created.
     * Sets up view binding, initializes the camera, detectors, and TTS,
     * checks for permissions, and binds UI button listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflate the layout using ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        uploadedImageView = binding.uploadedImageView

        uploadedImageView = binding.uploadedImageView  // ✅ ADD THIS LINE

        setContentView(binding.root)
        // Create a single-thread executor for background tasks like image analysis
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Initialize Text-to-Speech engine
        tts = TextToSpeech(this, this)
        // Run detection model initialization in a background thread
        cameraExecutor.execute {
            // Initialize the object detector (e.g., YOLO model)
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
            // Initialize specific detectors for walkway damage and sign detection
            walkwayDamageDetector = WalkwayDamageDetector(this@MainActivity, this@MainActivity)
            signDetector = SignDetector(this@MainActivity, this@MainActivity)
            // Prepare the detectors for use
            signDetector?.init()
            walkwayDamageDetector?.init()
        }
        // Check if all required permissions (like CAMERA) are granted
        if (allPermissionsGranted()) {
            // Start the camera preview and analysis
            startCamera()
        } else {
            // Request camera permission from the user
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        // Set up UI button listeners
        bindListeners()
    }

    /**
     * Initializes the CameraX camera provider and binds the camera use cases
     * (preview and image analysis) once the provider is ready.
     */
    private fun startCamera() {
        // Get a CameraProvider instance asynchronously
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Once the CameraProvider is available, bind the camera use cases
        cameraProviderFuture.addListener({
            // Get the actual camera provider
            cameraProvider = cameraProviderFuture.get()

            // Bind preview and image analysis use cases to the lifecycle
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this)) // Ensure callback runs on main thread
    }

    /**
     * Binds the camera preview and image analysis use cases using CameraX.
     * Configures camera settings like lens direction, aspect ratio, rotation, and output format.
     * Ensures the preview is displayed and frames are passed to the analyzer for detection.
     */
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Selects the back-facing camera
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Sets up the camera preview — the live feed shown on screen
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        // Sets up image analysis — used to process frames (e.g., for object detection or OCR)
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Discards old frames to avoid lag
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Required format for Bitmap conversion
            .build()

        // Unbinds any previous use cases (if camera was already running)
        cameraProvider.unbindAll()

        // Binds camera lifecycle with preview and analyzer use cases
        camera = cameraProvider.bindToLifecycle(
            this,              // Lifecycle owner (Activity)
            cameraSelector,    // Camera to use (back-facing)
            preview,           // Preview use case
            imageAnalyzer      // Image analysis use case
        )

        // Connects the preview to the ViewFinder UI element
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    /**
     * Binds click listeners to the UI buttons:
     * - Toggles between Sign and Sidewalk mode
     * - Captures or clears an image
     * - Launches the image picker
     * - Sends detection data to ChatGPT for description
     */
    private fun bindListeners() {

        // Toggle between Sign Detection and Sidewalk Damage Detection mode
        binding.isSignWalk.setOnClickListener {
            isSignMode = !isSignMode

            // Update button label and status text
            binding.isSignWalk.text = if (isSignMode) "Mode: Sidewalk" else "Mode: Sign"
            binding.modeStatus.text = if (isSignMode) "In: Sign Mode" else "In: Sidewalk Mode"

            // Restart the selected detector
            if (isSignMode) {
                signDetector?.restart()
                signDetector?.create()
            } else {
                walkwayDamageDetector?.restart()
                walkwayDamageDetector?.create()
            }
        }

        // Capture a frame from the live camera feed or clear the captured image
        binding.capture.setOnClickListener {
            if (isImageCaptured) {
                // Clear image and reset UI
                clearCapturedImage()
//            binding.uploadImageButton.visibility = View.GONE
            } else {
                // Capture a single frame for analysis
                captureOneFrame()
                binding.uploadImageButton.visibility = View.VISIBLE
            }
        }

        // Open image picker to upload an image from the gallery
        binding.uploadImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            pickImageLauncher.launch(intent)
        }

        // Send detected results (bounding boxes + OCR text) to ChatGPT for summary
        binding.chatButton.setOnClickListener {
            sendToChatGPT(detectionArray)
        }
    }

    /**
     * Handles an image selected from the gallery:
     * - Decodes the image into a Bitmap
     * - Converts it to ARGB_8888 format for processing
     * - Updates the UI to show the selected image
     * - Sends the image to the detector for object detection
     */
    private fun handleSelectedImage(uri: Uri) {
        try {
            // Decode the image based on Android version
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                // Use modern ImageDecoder API (Android 9+)
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                // Fallback for older Android versions
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            // Convert to ARGB_8888 to ensure compatibility with detection models
            val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Save the captured image and mark it as captured
            capturedBitmap = argbBitmap
            isImageCaptured = true
            isImageUploaded = true


            // Update the UI on the main thread
            runOnUiThread {
                binding.capture.text = "Clear Image"                            // Change button label
                binding.uploadImageButton.visibility = View.VISIBLE            // Show upload button
                binding.uploadedImageView.setImageBitmap(argbBitmap)          // Show selected image
                binding.uploadedImageView.visibility = View.VISIBLE
                cameraProvider?.unbindAll()                                    // Stop live camera preview
            }

            // Run object detection on the selected image
            detector?.detect(argbBitmap)

        } catch (e: Exception) {
            // Log decoding error if image loading fails
            Log.e("UPLOAD", "Failed to decode selected image", e)
        }
    }

    /**
     * Clears the currently captured or uploaded image and resets the UI state.
     * This includes clearing overlays, resetting detection data, and restarting the camera preview.
     */
    private fun clearCapturedImage() {
        // Reset capture state and image reference
        isImageCaptured = false
        isImageUploaded = false

        capturedBitmap = null

        // Clear visual overlays and any OCR text on screen
        binding.overlay.clear()
        binding.overlay.invalidate()
        binding.ocrTextView.text = ""

        // Reset detection array for new session
        detectionArray = JSONArray()

        // Reset the capture button text to default
        binding.capture.text = getString(R.string.capture_image)

        // Hide the uploaded image preview
        binding.uploadedImageView.setImageDrawable(null)
        binding.uploadedImageView.visibility = View.GONE

        // Show the upload button again (in case it was hidden)
        binding.uploadImageButton.visibility = View.VISIBLE

        // Ensure the overlay is visible (if hidden during image upload)
        binding.overlay.visibility = View.VISIBLE

        // Restart the camera preview to return to live detection mode
        startCamera()
    }

    /**
     * Captures a single frame from the live camera feed, processes it into a rotated Bitmap,
     * and sends it to the object detector. Also updates the UI to reflect capture state.
     */
    private fun captureOneFrame() {
        // Clear any existing analyzer to avoid conflicts
        imageAnalyzer?.clearAnalyzer()

        // Set a new analyzer to process the next available frame
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Create a Bitmap to hold the image data from the frame
            val bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height)

            // Copy the pixel data from the camera frame into the bitmap buffer
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            // Apply rotation and mirroring based on camera orientation
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) // Rotate to match device orientation
                if (isFrontCamera) postScale(-1f, 1f) // Mirror if using front camera
            }

            // Apply the matrix transformation to produce the final bitmap
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0,
                bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            // Close the image proxy to release resources
            imageProxy.close()

            // Save the captured and rotated bitmap
            capturedBitmap = rotatedBitmap
            isImageCaptured = true
            isImageUploaded = false


            // Update the UI on the main thread
            runOnUiThread {
                binding.capture.text = "Clear Image"
                cameraProvider?.unbindAll() // Stop the camera preview
            }

            // Run detection on the captured frame
            detector?.detect(rotatedBitmap)

            // Optional: Switch between detectors based on mode
            // if (isSignMode) signDetector?.detect() else walkwayDamageDetector?.detect()
        }
    }


    private fun sendToChatGPT(jsonInput: JSONArray) {
        val queue = Volley.newRequestQueue(this)

        // Build the prompt to send to ChatGPT
        val prompt = buildString {
            append("Given the following detected signs and their bounding boxes with OCR text, generate a natural language description of the scene:\n\n")
            append(detectionArray.toString(2)) // Format JSON for readability
        }

        val jsonBody = JSONObject().apply {
            put("model", "ft:gpt-3.5-turbo-0125:rakshit::B3TzUmAj")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")          // User input message
                    put("content", prompt)       // Prompt with detection info
                })
            })
        }

        // Create a POST request to send to the ChatGPT endpoint
        val request = object : JsonObjectRequest(
            Method.POST, chatGPTUrl, jsonBody,
            { response ->
                val result = response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                binding.ocrTextView.text = result // Display ChatGPT reply
            },
            { error ->
                Log.e("ChatGPT", "Error: ${error.message}")
            }
        ) {
            // Set the request headers including the API key and content type
            override fun getHeaders(): MutableMap<String, String> {
                return mutableMapOf(
                    "Authorization" to apiKey,                    // Replace with your real key
                    "Content-Type" to "application/json"
                )
            }
        }

        // Add the request to the queue for execution
        queue.add(request)
    }


    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            // Display inference time
            binding.inferenceTime.text = "${inferenceTime}ms"

            // Determine image dimensions
            val imgWidth = capturedBitmap?.width ?: binding.viewFinder.width
            val imgHeight = capturedBitmap?.height ?: binding.viewFinder.height
            Log.d("DEBUG", "Bounding boxes scaled for image size: $imgWidth x $imgHeight")

            // Use different views depending on source
            if (isImageUploaded) {
                uploadedImageView.setBoundingBoxes(boundingBoxes)
                binding.overlay.clear()
            } else {
                binding.overlay.setResults(boundingBoxes, imgWidth, imgHeight)
                uploadedImageView.setBoundingBoxes(emptyList())
                binding.overlay.invalidate()
            }


            // If nothing was detected, skip processing
            if (boundingBoxes.isEmpty()) return@runOnUiThread

            // Prepare to collect OCR results
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

                    // Convert normalized coordinates to pixels
                    val x1 = (box.x1 * w).toInt()
                    val y1 = (box.y1 * h).toInt()
                    val x2 = (box.x2 * w).toInt()
                    val y2 = (box.y2 * h).toInt()

                    // Log normalized and pixel coordinates
                    Log.d("COORDINATES", "Original: x1=${box.x1}, y1=${box.y1}, x2=${box.x2}, y2=${box.y2}")
                    Log.d("COORDINATES", "Pixel: x1=$x1, y1=$y1, x2=$x2, y2=$y2")

                    // Safeguard crop size
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


    /**
     * Runs text recognition (OCR) on a cropped bitmap, appends the result to the output string,
     * and adds the detection with bounding box data to the shared detection array.
     */
    private fun runTextRecognition(
        bitmap: Bitmap,              // The cropped image region to analyze (from a detection box)
        label: String,               // The object class label (e.g., "Stop Sign")
        resultBuilder: StringBuilder, // Shared text builder to collect OCR results for all boxes
        index: Int,                  // Index of the current bounding box (used for numbering)
        box: BoundingBox,           // The original bounding box containing normalized coordinates
        imageWidth: Int,            // Width of the full original image (for denormalizing coordinates)
        imageHeight: Int,           // Height of the full original image
        onComplete: () -> Unit      // Callback triggered after OCR completes (success or failure)
    ) {
        // Convert bitmap into an InputImage required by ML Kit
        val image = InputImage.fromBitmap(bitmap, 0)

        // Get a default text recognizer client
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Process the image asynchronously
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Extract and clean the recognized text
                val text = visionText.text.trim().takeIf { it.isNotEmpty() } ?: "N/A"

                // Append formatted result to shared text output
                resultBuilder.append("${index + 1}. $label - $text\n")

                // Normalize bounding box coordinates relative to the full image size
//                val norm = normalizeBoundingBox(
//                    (box.x1 * imageWidth).toInt(),
//                    (box.y1 * imageHeight).toInt(),
//                    (box.x2 * imageWidth).toInt(),
//                    (box.y2 * imageHeight).toInt(),
//                    imageWidth, imageHeight
//                )

                // Create a structured JSON object for this detection
                val detectionObject = JSONObject().apply {
                    put("label", label)
                    put("ocr_text", text.replace("\n", " "))
                    put("bounding_box", JSONObject().apply {
                        put("x1", "%.4f".format(box.x1))
                        put("y1", "%.4f".format(box.y1))
                        put("x2", "%.4f".format(box.x2))
                        put("y2", "%.4f".format(box.y2))
                    })
                }


                // Add the result to the global detection array
                detectionArray.put(detectionObject)

                // Speak the result aloud
                tts.speak("$label detected. Text is $text", TextToSpeech.QUEUE_ADD, null, null)

                // Notify that OCR is done for this box
                onComplete()
            }
            .addOnFailureListener {
                // On failure, log and still notify completion
                Log.e("OCR", "Text recognition failed", it)
                resultBuilder.append("${index + 1}. $label - N/A\n")
                onComplete()
            }
    }


    /**
     * Normalizes a bounding box's pixel coordinates to values between 0 and 1
     * relative to the full image size. This is useful for creating resolution-independent
     * bounding boxes that can be used consistently across devices or in JSON outputs.
     */
    private fun normalizeBoundingBox(
        xMin: Int,
        yMin: Int,
        xMax: Int,
        yMax: Int,
        imageWidth: Int,
        imageHeight: Int
    ): List<Float> {
        return listOf(
            xMin.toFloat() / imageWidth,                    // Normalized x (left)
            yMin.toFloat() / imageHeight,                   // Normalized y (top)
            (xMax - xMin).toFloat() / imageWidth,           // Normalized width
            (yMax - yMin).toFloat() / imageHeight           // Normalized height
        )
    }

    /**
     * Determines the relative horizontal position of a detected object on the screen
     * (left, center, or right) based on the center X position of its bounding box.
     *
     * @param box The bounding box containing normalized coordinates (0 to 1)
     * @return A direction string: "on the left", "on front", or "on the right"
     */
    private fun getDirection(box: BoundingBox): String {
        // Compute the center X position of the bounding box (still normalized [0,1])
        val centerX = (box.x1 + box.x2) / 2

        // Get the width of the camera preview view in pixels
        val screenWidth = binding.viewFinder.width

        // Compare center X to screen thirds to determine direction
        return when {
            centerX < screenWidth / 3 -> "on the left"
            centerX > 2 * screenWidth / 3 -> "on the right"
            else -> "on front"
        }
    }


    /**
     * Speaks the provided label using the Text-to-Speech (TTS) engine.
     * Clears any existing speech queue and immediately reads out the new label.
     *
     * @param label The text to be spoken aloud (e.g., detected object name)
     */
    private fun speakDetectedLabel(label: String) {
        // Speak the label if it's not empty; replaces anything currently being spoken
        if (label.isNotEmpty()) {
            tts.speak(label, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }


    /**
     * Callback for when the Text-to-Speech (TTS) engine has finished initializing.
     * Sets the language to US English if successful; logs an error otherwise.
     *
     * @param status Indicates the result of the TTS engine initialization.
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set the TTS language to US English
            tts.language = Locale.US
        } else {
            // Log an error if initialization failed
            Log.e(TAG, "TTS initialization failed")
        }
    }


    // Called when no detection is made, clears overlays and output
    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
            binding.ocrTextView.text = ""
            detectionArray = JSONArray()
        }
    }

    // Cleans up resources: shuts down detector, camera thread, and TTS
    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    // Resumes camera preview when activity regains focus
    override fun onResume() {
        super.onResume()
        if (!isImageCaptured) {
            if (allPermissionsGranted()) startCamera()
            else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }
    // Checks if all required permissions (like camera) are granted
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
