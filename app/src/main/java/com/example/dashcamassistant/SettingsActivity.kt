package com.example.dashcamassistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dashcamassistant.databinding.ActivitySettingsBinding
import android.util.Log

// Обработчик экрана меню
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var calibrationHelper: CalibrationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Вспомогательный класс для работы с файлом маски
        calibrationHelper = CalibrationHelper(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Настройки"

        // Кнопка калибровки
        binding.btnCalibrate.setOnClickListener {
            startCalibration()
        }

        // Кнопка показа маски
        binding.btnShowMask.setOnClickListener {
            showMaskOverlay()
        }

        // Кнопка сброса калибровки
        binding.btnResetCalibration.setOnClickListener {
            Log.d("SettingsActivity", "Сброс калибровки нажат")
            calibrationHelper.resetCalibration()
            Toast.makeText(this, "Калибровка сброшена", Toast.LENGTH_SHORT).show()
            updateCalibrationStatus()
        }

        // Обновляем надпись о статусе калибровки
        updateCalibrationStatus()
    }

    // Метод для показа маски
    private fun showMaskOverlay() {
        // Проверяем, есть ли сохранённая маска
        if (!calibrationHelper.hasCalibration()) {
            Toast.makeText(this, "Нет сохранённой маски. Сначала выполните калибровку.", Toast.LENGTH_LONG).show()
            return
        }

        // Запускаем отдельную активность с маской поверх камеры
        // FLAG_ACTIVITY_NEW_TASK -  в новом окне
        // FLAG_ACTIVITY_CLEAR_TOP - закрываем все окна над ним
        val intent = Intent(this, MaskOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    // Метод для запуска калибровки
    private fun startCalibration() {
        Log.d("SettingsActivity", "startCalibration начала работу")
        // Отправляем флаг о начале калибровки в MainActivity через Intent
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("start_calibration", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()  // закрываем настройки
    }

    // Метод для обнавления статуса калибровки
    private fun updateCalibrationStatus() {
        val hasCalibration = calibrationHelper.hasCalibration()
        binding.tvCalibrationStatus.text = if (hasCalibration) "Калибровка выполнена" else "Калибровка не выполнена"
    }

    // Выход из активности
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}