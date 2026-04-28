package com.example.dashcamassistant

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class AutoCalibration {

    companion object {
        private const val TAG = "AutoCalibration"
        private const val SAMPLE_FRAMES = 250      // 30 секунд при 5 кадрах/сек
        private const val STATIC_THRESHOLD = 5    // порог изменения пикселя
    }

    private var framesAccumulated = 0
    private var accumulatedDiff: Mat? = null
    private var firstFrame: Mat? = null

    // Добавляем кадр для анализа
    fun addFrame(frame: Mat): Bitmap? {
        // Конвертируем в оттенки серого
        val grayFrame = Mat()
        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY)

        if (firstFrame == null) {
            firstFrame = grayFrame.clone()
            accumulatedDiff = Mat.zeros(grayFrame.rows(), grayFrame.cols(), CvType.CV_32FC1)
            framesAccumulated = 0
            grayFrame.release()
            Log.d(TAG, "Первый кадр сохранён")
            return null
        }

        // Вычисляем разницу с первым кадром
        val diff = Mat()
        Core.absdiff(firstFrame, grayFrame, diff)

        // Накапливаем изменения
        val floatDiff = Mat()
        diff.convertTo(floatDiff, CvType.CV_32FC1)
        Core.add(accumulatedDiff, floatDiff, accumulatedDiff)
        framesAccumulated++

        diff.release()
        floatDiff.release()
        grayFrame.release()

        Log.d(TAG, "Калибровка: $framesAccumulated / $SAMPLE_FRAMES кадров")

        // Если набрали достаточно кадров - вычисляем маску
        if (framesAccumulated >= SAMPLE_FRAMES) {
            Log.d(TAG, "Начинаем создание маски, кадров накоплено: $framesAccumulated")
            val mask = calculateMask()
            Log.d(TAG, "Маска создана: ${mask.width}x${mask.height}")
            return mask
        }

        return null
    }

    private fun calculateMask(): Bitmap {
        // Усредняем различия
        val avgDiff = Mat()
        Core.divide(accumulatedDiff, Scalar(framesAccumulated.toDouble()), avgDiff)
        Log.d(TAG, "avgDiff - min: ${Core.minMaxLoc(avgDiff).minVal}, max: ${Core.minMaxLoc(avgDiff).maxVal}")

        // Пороговая обработка
        val mask = Mat()
        Imgproc.threshold(avgDiff, mask, STATIC_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
        val nonZero = Core.countNonZero(mask)
        Log.d(TAG, "mask после threshold: ненулевых пикселей $nonZero из ${mask.rows() * mask.cols()}")

        // Конвертируем в 8-bit
        val mask8u = Mat()
        mask.convertTo(mask8u, CvType.CV_8UC1)

        // Конвертируем в Bitmap
        val bitmap = Bitmap.createBitmap(mask8u.cols(), mask8u.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mask8u, bitmap)

        // Очистка
        avgDiff.release()
        mask.release()
        mask8u.release()

        return bitmap
    }

    fun reset() {
        firstFrame?.release()
        accumulatedDiff?.release()
        firstFrame = null
        accumulatedDiff = null
        framesAccumulated = 0
        Log.d(TAG, "AutoCalibration сброшена")
    }
}