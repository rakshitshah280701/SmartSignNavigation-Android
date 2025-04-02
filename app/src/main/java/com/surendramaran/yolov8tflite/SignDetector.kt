package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class SignDetector(
    context: Context,
    detectorListener: Detector.DetectorListener
) : Detector(
    context = context,
    modelPath = "best_float32.tflite",       // Replace with your actual model filename
    labelPath = "labels.txt",         // Replace with your actual label filename
    detectorListener = detectorListener
) {

    // will need to be overriden later
    fun restart() {
        Log.d("DEBUG_TAG", "SignDetector restarting")
    }

    fun init() {
        Log.d("DEBUG_TAG", "SignDetector initializing")
    }

    fun create() {
        Log.d("DEBUG_TAG", "SignDetector model creation")
    }

    // will need to be overridden later
    fun detect() {
        Log.d("DEBUG_TAG", "SignDetector running")
    }
}
