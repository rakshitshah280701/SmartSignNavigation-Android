package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class WalkwayDamageDetector(
    context: Context,
    detectorListener: Detector.DetectorListener
) : Detector(
    context = context,
    modelPath = "best_float32.tflite",       // Replace with your actual model filename
    labelPath = "labels.txt",         // Replace with your actual label filename
    detectorListener = detectorListener
) {

    fun restart() {
        Log.d("DEBUG_TAG", "WalkwayDamageDetector restarting")
    }

    fun init() {
        Log.d("DEBUG_TAG", "WalkwayDamageDetector initializing")
    }

    fun create() {
        Log.d("DEBUG_TAG", "WalkwayDamageDetector model creation")
    }

    // will need to be overridden later
    fun detect() {
        Log.d("DEBUG_TAG", "WalkwayDamageDetector running")
    }
}
