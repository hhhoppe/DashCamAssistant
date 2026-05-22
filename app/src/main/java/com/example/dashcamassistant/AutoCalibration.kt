package com.example.dashcamassistant

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

// Хранит логику обработки кадров для маски
class AutoCalibration {

    companion object {
        private const val TAG = "AutoCalibration"
        private const val SAMPLE_FRAMES = 160      // Необходимое кол-во кадров
        private const val STATIC_THRESHOLD = 15    // Запасной порог на изменение пикселя
        private const val LOWER_THRESHOLD = 5      // Меньший порог для тёмных участков
        private const val ADAPTIVE_PERCENT = 0.15  // Адаптивный порог под текущее освещение
    }

    private var framesAccumulated = 0           // Кол-во накопленных кадров
    private var accumulatedDiff: Mat? = null    // Сумма изменений между кадрами
    private var firstFrame: Mat? = null         // Первый взятый кадр

    // Метод для добавления кадра в анализ
    fun addFrame(frame: Mat): Bitmap? {
        // Конвертируем в оттенки серого
        val grayFrame = Mat()
        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY)

        // Если взятый кадр является первым, запоминаем его для дальнейшего сравнения
        if (firstFrame == null) {
            firstFrame = grayFrame.clone()
            accumulatedDiff = Mat.zeros(grayFrame.rows(), grayFrame.cols(), CvType.CV_32FC1)
            framesAccumulated = 1
            grayFrame.release()
            Log.d(TAG, "Первый кадр сохранён")
            return null
        }

        // Вычисляем разницу с первым кадром
        val diff = Mat()
        Core.absdiff(firstFrame, grayFrame, diff)

        // Сохраняем разницу
        val floatDiff = Mat()
        diff.convertTo(floatDiff, CvType.CV_32FC1)
        Core.add(accumulatedDiff, floatDiff, accumulatedDiff)
        framesAccumulated++

        diff.release()
        floatDiff.release()
        grayFrame.release()

        Log.d(TAG, "Калибровка: $framesAccumulated / $SAMPLE_FRAMES кадров")

        // Если собрали нужное кол-во кадров - вычисляем маску
        if (framesAccumulated >= SAMPLE_FRAMES) {
            Log.d(TAG, "Создание маски, кадров накоплено: $framesAccumulated")

            // Вычисляем порог по полученной яркости картинки
            val adaptiveThreshold = calculateAdaptiveThreshold()

            // Пробуем создать маску с ним
            var mask = calculateMask(adaptiveThreshold)

            // Проверяем качество созданной маски
            val nonZero = Core.countNonZero(mask)       // ненулевых пиксели
            val totalPixels = mask.rows() * mask.cols()       // кол-во пикселей
            val staticPercent = nonZero * 100.0 / totalPixels // статичные пиксели

            Log.d(TAG, "Адаптивный порог: $adaptiveThreshold, статичные пиксели: ${String.format("%.1f", staticPercent)}%")

            // Если маска слишком пустая (<5%) или слишком полная (>95%) - пробуем другие пороги
            if (staticPercent < 5.0) {
                Log.w(TAG, "Маска слишком пустая (${String.format("%.1f", staticPercent)}%), пробуем меньший порог")
                mask.release()
                mask = calculateMask(LOWER_THRESHOLD.toDouble())
            } else if (staticPercent > 95.0) {
                Log.w(TAG, "Маска слишком полная (${String.format("%.1f", staticPercent)}%), пробуем стандартный порог")
                mask.release()
                mask = calculateMask(STATIC_THRESHOLD.toDouble())
            }

            // Сохраняем маску
            val result = matToBitmap(mask)
            mask.release()
            reset()
            return result
        }

        return null
    }

    // Метод для вычисления адаптивного порога (ярко - выше, темно - ниже)
    private fun calculateAdaptiveThreshold(): Double {
        // Усредняем накопленную разницу по всем кадрам
        val avgDiff = Mat()
        Core.divide(accumulatedDiff, Scalar(framesAccumulated.toDouble()), avgDiff)

        // Находим максимальное значение разницы
        val minMax = Core.minMaxLoc(avgDiff)
        val maxVal = minMax.maxVal

        avgDiff.release()

        // Берём 15% от максимальной разницы, но не меньше 5 и не больше 30
        val adaptiveThreshold = max(5.0, maxVal * ADAPTIVE_PERCENT)
        val clamped = adaptiveThreshold.coerceAtMost(30.0)

        Log.d(TAG, "Адаптивный порог: maxVal=$maxVal, ${ADAPTIVE_PERCENT * 100}% = $adaptiveThreshold, итого=$clamped")

        return clamped
    }

    // Функция для создания маски
    private fun calculateMask(threshold: Double): Mat {
        Log.d(TAG, "calculateMask() начал работу с порогом: $threshold")

        // Усредняем накопленную разницу по всем кадрам
        val avgDiff = Mat()
        Core.divide(accumulatedDiff, Scalar(framesAccumulated.toDouble()), avgDiff)
        Log.d(TAG, "avgDiff создан, размер: ${avgDiff.rows()}x${avgDiff.cols()}")
        val minMax = Core.minMaxLoc(avgDiff)
        Log.d(TAG, "avgDiff min=${minMax.minVal}, max=${minMax.maxVal}")

        // Белые - статичные пиксели
        val mask = Mat()
        Imgproc.threshold(avgDiff, mask, threshold, 255.0, Imgproc.THRESH_BINARY_INV)
        Log.d(TAG, "threshold выполнен")

        // Проверка
        val nonZero = Core.countNonZero(mask)
        val totalPixels = mask.rows() * mask.cols()
        Log.d(TAG, "mask: ненулевых пикселей = $nonZero из $totalPixels (${nonZero * 100 / totalPixels}%)")
        if (nonZero == 0) {
            Log.w(TAG, "Маска полностью состоит из ненулевых пикселей.")
        }

        // Очистка
        avgDiff.release()

        return mask
    }

    // Конвертация Mat из OpenCV в Bitmap
    private fun matToBitmap(mat: Mat): Bitmap {
        // Конвертируем в 8-bit если нужно
        val mask8u = if (mat.type() != CvType.CV_8UC1) {
            val temp = Mat()
            mat.convertTo(temp, CvType.CV_8UC1)
            temp
        } else {
            mat.clone()
        }

        val bitmap = Bitmap.createBitmap(mask8u.cols(), mask8u.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mask8u, bitmap)

        mask8u.release()

        Log.d(TAG, "Bitmap создан, размер: ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    // Метод для сброса калибровки
    fun reset() {
        firstFrame?.release()
        accumulatedDiff?.release()
        firstFrame = null
        accumulatedDiff = null
        framesAccumulated = 0
        Log.d(TAG, "AutoCalibration сброшена")
    }
}