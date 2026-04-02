package com.example.dashcamassistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dashcamassistant.databinding.ActivitySettingsBinding

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

        // Кнопка сброса калибровки
        binding.btnResetCalibration.setOnClickListener {
            calibrationHelper.resetCalibration()
            Toast.makeText(this, "Калибровка сброшена", Toast.LENGTH_SHORT).show()
            updateCalibrationStatus()
        }

        updateCalibrationStatus()
    }

    private fun startCalibration() {
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