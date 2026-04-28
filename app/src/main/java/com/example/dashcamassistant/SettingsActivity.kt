package com.example.dashcamassistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dashcamassistant.databinding.ActivitySettingsBinding
import android.util.Log

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var calibrationHelper: CalibrationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calibrationHelper = CalibrationHelper(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Настройки"

        // Кнопка калибровки
        binding.btnCalibrate.setOnClickListener {
            startCalibration()
        }

        // Кнопка показа маски (поверх камеры)
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

        updateCalibrationStatus()
    }

    private fun showMaskOverlay() {
        // Проверяем, есть ли маска
        if (!calibrationHelper.hasCalibration()) {
            Toast.makeText(this, "Нет сохранённой маски. Сначала выполните калибровку.", Toast.LENGTH_LONG).show()
            return
        }

        // Запускаем активность с маской поверх камеры
        // Используем FLAG_ACTIVITY_NEW_TASK для изоляции
        val intent = Intent(this, MaskOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun startCalibration() {
        Log.d("SettingsActivity", "startCalibration нажата")
        // Отправляем сигнал в MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("start_calibration", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()  // закрываем настройки
    }

    private fun updateCalibrationStatus() {
        val hasCalibration = calibrationHelper.hasCalibration()
        binding.tvCalibrationStatus.text = if (hasCalibration) "✓ Калибровка выполнена" else "✗ Калибровка не выполнена"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}