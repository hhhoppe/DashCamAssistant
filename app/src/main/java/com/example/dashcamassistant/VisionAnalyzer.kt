package com.example.dashcamassistant

import android.content.Context
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import org.opencv.android.Utils

class VisionAnalyzer(
    context: Context,
    private val isCarMovingProvider: () -> Boolean,
    private val onMovementDetected: () -> Unit
) : ImageAnalysis.Analyzer, SensorEventListener {

    companion object {
        private const val TAG = "VisionAnalyzer"

        // Настройки
        private const val MOVEMENT_THRESHOLD = 120      // Минимальное смещение объекта
        private const val MIN_CONTOUR_AREA = 2500       // Минимальный размер объекта
        private const val MIN_CAR_WIDTH = 100           // Минимальная ширина машины
        private const val STATIONARY_THRESHOLD = 15     // порог "стоит"
        private const val ACCELEROMETER_THRESHOLD = 0.5f
        private const val COOLDOWN_MS = 3000            // Пауза между срабатываниями
        private var noCalibrationLogged = false
    }

    private var previousFrame: Mat? = null
    private var previousCarRect: Rect? = null
    private var wasCarStationary = false
    private var lastTriggerTime = 0L
    private var detectionCounter = 0

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isPhoneMoving = false

    private var movementCounter = 0
    private var stableCounter = 0

    private var calibrationMask: Mat? = null
    var isCalibrating = false
    private val autoCalibration = AutoCalibration()
    private var calibrationCallback: ((Boolean) -> Unit)? = null
    private var calibrationFrameCount = 0
    private val NEED_FRAMES = 150

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed")
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Загружаем сохранённую маску
        val calibrationHelper = CalibrationHelper(context)
        val savedMask = calibrationHelper.loadMask()
        if (savedMask != null) {
            loadMask(savedMask)
            Log.d(TAG, "Загружена сохранённая калибровка")
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val currentFrame = imageProxy.toMat() ?: run {
            imageProxy.close()
            return
        }

        // Режим калибровки
        if (isCalibrating) {
            calibrationFrameCount++
            Log.d(TAG, "Калибровка: кадр ${calibrationFrameCount}/$NEED_FRAMES")

            val maskBitmap = autoCalibration.addFrame(currentFrame)

            // Если получили маску ИЛИ набрали достаточно кадров
            if (maskBitmap != null) {
                isCalibrating = false
                loadMask(maskBitmap)
                calibrationCallback?.invoke(true)
                Log.d(TAG, "Калибровка завершена успешно! Маска получена на кадре $calibrationFrameCount")
                calibrationFrameCount = 0
            } else if (calibrationFrameCount >= NEED_FRAMES) {
                isCalibrating = false
                calibrationCallback?.invoke(false)
                Log.d(TAG, "Калибровка завершена с ошибкой: маска не создалась")
                calibrationFrameCount = 0
            }

            currentFrame.release()
            imageProxy.close()
            return
        }

        if (calibrationMask == null) {
            // Один раз логируем, что калибровки нет
            if (!noCalibrationLogged) {
                Log.d(TAG, "Нет калибровки, анализ отключён")
                noCalibrationLogged = true
            }
            currentFrame.release()
            imageProxy.close()
            return
        }
        noCalibrationLogged = false

        detectMovement(currentFrame)

        previousFrame?.release()
        previousFrame = currentFrame
        imageProxy.close()
    }

    private fun detectMovement(currentFrame: Mat) {
        if (previousFrame == null) return

        // Проверка на кулдаун после последнего срабатывания
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < COOLDOWN_MS) {
            Log.d(TAG, "Кулдаун: ${(now - lastTriggerTime)}ms")
            previousCarRect = null
            wasCarStationary = false
            return
        }

        // Если телефон движется ИЛИ машина едет - не анализируем
        if (isPhoneMoving || isCarMovingProvider()) {
            previousCarRect = null
            wasCarStationary = false
            return
        }

        val diff = Mat()
        Core.absdiff(previousFrame, currentFrame, diff)

        val gray = Mat()
        Imgproc.cvtColor(diff, gray, Imgproc.COLOR_BGR2GRAY)

        Imgproc.threshold(gray, gray, 30.0, 255.0, Imgproc.THRESH_BINARY)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(8.0, 8.0))
        Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_OPEN, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(gray, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        // Проверка по маске калибровки: игнорируем зоны, отмеченные белым
        if (calibrationMask != null) {
            // Удаляем контуры, которые попадают в запрещённую зону
            contours.removeAll { contour ->
                val rect = Imgproc.boundingRect(contour)
                val centerX = rect.x + rect.width / 2
                val centerY = rect.y + rect.height / 2

                // Проверяем, что центр контура не попадает в белую область маски
                centerY < calibrationMask!!.rows() &&
                        centerX < calibrationMask!!.cols() &&
                        calibrationMask!!.get(centerY, centerX)[0] > 200.0  // белый - игнорируем
            }
        }

        val frameHeight = currentFrame.rows()
        val frameWidth = currentFrame.cols()
        val bottomHalfY = frameHeight / 2
        val centerRegionX = frameWidth / 4  // центральная зона по горизонтали

        // Ищем подходящий контур
        val carContour = contours
            .filter { contour ->
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val width = rect.width
                val height = rect.height

                // Условия для машины:
                area > MIN_CONTOUR_AREA &&                    // достаточно большая
                        width > MIN_CAR_WIDTH &&              // широкая
                        width > height &&                     // шире чем высота (машина)
                        rect.x + width > centerRegionX &&     // не слишком слева
                        rect.x < frameWidth - centerRegionX   // не слишком справа
            }
            .maxByOrNull { Imgproc.contourArea(it) }

        if (carContour != null) {
            val boundingRect = Imgproc.boundingRect(carContour)

            // Контур находится в нижней половине кадра
            if (boundingRect.y + boundingRect.height > bottomHalfY) {

                if (previousCarRect != null) {
                    val prevCenterX = previousCarRect!!.left + previousCarRect!!.width() / 2
                    val prevCenterY = previousCarRect!!.top + previousCarRect!!.height() / 2
                    val currCenterX = boundingRect.x + boundingRect.width / 2
                    val currCenterY = boundingRect.y + boundingRect.height / 2

                    val dx = currCenterX - prevCenterX
                    val dy = currCenterY - prevCenterY

                    val movement = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())

                    // Лог для отладки
                    if (movement > 20) {
                        Log.d(TAG, "Движение: ${"%.1f".format(movement)} пикс, dx=$dx, dy=$dy, area=${Imgproc.contourArea(carContour)}")
                    }

                    // Основная логика
                    if (movement > MOVEMENT_THRESHOLD && wasCarStationary) {
                        detectionCounter++
                        if (detectionCounter > 2) {  // надо 3 подтверждения подряд
                            Log.d(TAG, "Впереди машина поехала! movement=${"%.1f".format(movement)}")
                            onMovementDetected()
                            lastTriggerTime = now
                            detectionCounter = 0
                            wasCarStationary = false
                        }
                    } else if (movement < STATIONARY_THRESHOLD) {
                        detectionCounter = 0
                        wasCarStationary = true
                    } else {
                        detectionCounter = 0
                    }
                }

                previousCarRect = Rect(
                    boundingRect.x,
                    boundingRect.y,
                    boundingRect.x + boundingRect.width,
                    boundingRect.y + boundingRect.height
                )
            } else {
                previousCarRect = null
                wasCarStationary = false
                detectionCounter = 0
            }
        } else {
            previousCarRect = null
            wasCarStationary = false
            detectionCounter = 0
        }

        diff.release()
        gray.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val acceleration = kotlin.math.sqrt(x * x + y * y + z * z)

            if (acceleration > ACCELEROMETER_THRESHOLD + 9.8) {
                movementCounter++
                if (movementCounter > 3) {
                    isPhoneMoving = true
                }
            } else {
                stableCounter++
                if (stableCounter > 5) {
                    isPhoneMoving = false
                    movementCounter = 0
                    stableCounter = 0
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun ImageProxy.toMat(): Mat? {
        return try {
            val planes = this.planes
            if (planes.isEmpty()) return null

            val buffer = planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val mat = Mat(this.height, this.width, CvType.CV_8UC1)
            mat.put(0, 0, data)

            val rgbMat = Mat()
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_GRAY2RGB)
            mat.release()

            rgbMat
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Mat: ${e.message}")
            null
        }
    }

    fun release() {
        sensorManager.unregisterListener(this)
    }

    fun startCalibration(callback: (Boolean) -> Unit) {
        Log.d(TAG, "startCalibration вызван! isCalibrating устанавливается в true")
        calibrationCallback = callback
        isCalibrating = true
        autoCalibration.reset()
        previousFrame?.release()
        previousFrame = null
        previousCarRect = null
        wasCarStationary = false
        Log.d(TAG, "Начало автоматической калибровки, isCalibrating=$isCalibrating")
    }

    fun loadMask(maskBitmap: Bitmap?) {
        if (maskBitmap == null) {
            calibrationMask?.release()
            calibrationMask = null
            return
        }

        val maskMat = Mat()
        Utils.bitmapToMat(maskBitmap, maskMat)
        val grayMask = Mat()
        Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_BGRA2GRAY)
        calibrationMask?.release()
        calibrationMask = grayMask.clone()
        grayMask.release()
        maskMat.release()
    }

    fun getMaskBitmap(): Bitmap? {
        if (calibrationMask == null) return null
        val bitmap = Bitmap.createBitmap(calibrationMask!!.cols(), calibrationMask!!.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(calibrationMask!!, bitmap)
        return bitmap
    }
}