package com.surendramaran.yolov8tflite;

abstract class AbstractDetector {
    abstract fun restart()
    abstract fun init()
    abstract fun create()
    abstract fun detect()
}
