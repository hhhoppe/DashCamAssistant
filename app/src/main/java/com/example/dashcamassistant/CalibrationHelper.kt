package com.example.dashcamassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

// Управляет маской
class CalibrationHelper(private val context: Context) {

    companion object {
        private const val MASK_FILE = "calibration_mask.png"
    }

    private val maskFile: File
        get() = File(context.filesDir, MASK_FILE)

    // Сохраняем маску
    fun saveMask(mask: Bitmap): Boolean {
        return try {
            FileOutputStream(maskFile).use { outputStream ->
                mask.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Загружаем маску
    fun loadMask(): Bitmap? {
        return if (maskFile.exists()) {
            BitmapFactory.decodeFile(maskFile.absolutePath)
        } else null
    }

    // Проверяем есть ли сохранённая маска
    fun hasCalibration(): Boolean = maskFile.exists()

    // Сброс маски калибровки
    fun resetCalibration(): Boolean {
        return if (maskFile.exists()) {
            maskFile.delete()
        } else true
    }
}