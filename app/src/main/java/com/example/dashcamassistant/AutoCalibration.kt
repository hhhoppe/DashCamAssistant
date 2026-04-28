package com.example.dashcamassistant

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class AutoCalibration {

    companion object {
        private const val TAG = "AutoCalibration"
        private const val SAMPLE_FRAMES = 150      // 30 секунд при 5 кадрах/сек
        private const val STATIC_THRESHOLD = 15    // порог изменения пикселя
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
            val mask = calculateMask()
            reset()  // сбрасываем состояние
            return mask
        }

        return null
    }

    private fun calculateMask(): Bitmap {
        // Усредняем накопленные изменения
        val avgDiff = Mat()
        Core.divide(accumulatedDiff, Scalar(framesAccumulated.toDouble()), avgDiff)

        // Нормализуем для визуализации
        val normalized = Mat()
        Core.normalize(avgDiff, normalized, 0.0, 255.0, Core.NORM_MINMAX)

        // Статические области (чёрные) - где изменения минимальны
        val staticMask = Mat()
        Imgproc.threshold(normalized, staticMask, STATIC_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)

        // Преобразуем в 8-bit для сохранения
        val mask8u = Mat()
        staticMask.convertTo(mask8u, CvType.CV_8UC1)

        // Морфологическое закрытие для заполнения дырок
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closedMask = Mat()
        Imgproc.morphologyEx(mask8u, closedMask, Imgproc.MORPH_CLOSE, kernel)

        // Дополнительно: инвертируем (черный - анализируем, белый - игнорируем)
        val invertedMask = Mat()
        Core.bitwise_not(closedMask, invertedMask)

        // Конвертируем Mat в Bitmap
        val bitmap = Bitmap.createBitmap(invertedMask.cols(), invertedMask.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(invertedMask, bitmap)

        // Очистка
        avgDiff.release()
        normalized.release()
        staticMask.release()
        mask8u.release()
        kernel.release()
        closedMask.release()
        invertedMask.release()

        Log.d(TAG, "Калибровка завершена: маска создана")

        return bitmap
    }

    fun reset() {
        firstFrame?.release()
        accumulatedDiff?.release()
        firstFrame = null
        accumulatedDiff = null
        framesAccumulated = 0
    }
}