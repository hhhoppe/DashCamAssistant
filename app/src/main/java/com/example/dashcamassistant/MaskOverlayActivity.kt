package com.example.dashcamassistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MaskOverlayActivity : AppCompatActivity() {

    private lateinit var ivMask: ImageView
    private lateinit var seekBarAlpha: SeekBar
    private lateinit var tvAlpha: TextView
    private lateinit var btnClose: Button
    private lateinit var btnToggleOverlay: Button
    private lateinit var tvInfo: TextView
    private lateinit var previewView: androidx.camera.view.PreviewView

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var originalMask: Bitmap? = null
    private var isOverlayVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mask_overlay)

        // На всю ширину, без статус-бара
        WindowCompat.setDecorFitsSystemWindows(window, false)

        initViews()
        loadMask()
        setupListeners()
        startCamera()
    }

    override fun onResume() {
        super.onResume()
        if (cameraProvider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
        originalMask?.recycle()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        ivMask = findViewById(R.id.ivMask)
        seekBarAlpha = findViewById(R.id.seekBarAlpha)
        tvAlpha = findViewById(R.id.tvAlpha)
        btnClose = findViewById(R.id.btnClose)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        tvInfo = findViewById(R.id.tvInfo)

        // Настройки прозрачности
        seekBarAlpha.max = 100
        seekBarAlpha.progress = 10 // 10% видимости по умолчанию
    }

    private fun loadMask() {
        val calibrationHelper = CalibrationHelper(this)
        originalMask = calibrationHelper.loadMask()

        if (originalMask != null) {
            // Поворачиваем маску в соответствии с ориентацией экрана
            val rotatedMask = rotateMaskForDisplay(originalMask!!)

            ivMask.setImageBitmap(rotatedMask)

            // Применяем прозрачность после загрузки маски
            val alpha = seekBarAlpha.progress / 100f
            ivMask.alpha = alpha
            tvAlpha.text = "Прозрачность маски: ${(alpha * 100).toInt()}%"

            tvInfo.text = "Маска загружена\nБелые зоны - игнорируются\nЧёрные зоны - анализируются"
            tvInfo.setTextColor(Color.GREEN)
        } else {
            ivMask.setImageBitmap(null)
            tvInfo.text = "Нет сохранённой маски\nСначала выполните калибровку"
            tvInfo.setTextColor(Color.RED)
            seekBarAlpha.isEnabled = false
            btnToggleOverlay.isEnabled = false
        }
    }

    private fun rotateMaskForDisplay(mask: Bitmap): Bitmap {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Определяем текущую ориентацию экрана
        val rotation = windowManager.defaultDisplay.rotation
        val isPortrait = screenHeight > screenWidth

        // Маска из калибровки всегда в landscape (ширина > высоты)
        val isMaskLandscape = mask.width > mask.height

        return when {
            // Портретная ориентация, маска горизонтальная - поворот НЕ нужен, просто масштабируем
            isPortrait && isMaskLandscape -> {
                Bitmap.createScaledBitmap(mask, screenWidth, screenHeight, true)
            }
            // Ландшафтная ориентация, маска горизонтальная - поворот на 90
            !isPortrait && isMaskLandscape -> {
                val matrix = Matrix()
                matrix.postRotate(90f)
                val rotated = Bitmap.createBitmap(mask, 0, 0, mask.width, mask.height, matrix, true)
                Bitmap.createScaledBitmap(rotated, screenWidth, screenHeight, true)
            }
            else -> {
                Bitmap.createScaledBitmap(mask, screenWidth, screenHeight, true)
            }
        }
    }

    private fun setupListeners() {
        // Закрыть активность
        btnClose.setOnClickListener {
            finish()
        }

        // Показать/скрыть маску
        btnToggleOverlay.setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            ivMask.visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
            btnToggleOverlay.text = if (isOverlayVisible) "Скрыть маску" else "Показать маску"
        }

        // Настройка прозрачности
        seekBarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f
                ivMask.alpha = alpha
                tvAlpha.text = "Прозрачность маски: ${(alpha * 100).toInt()}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startCamera() {
        // Не запускаем камеру, если она уже есть и активна
        if (cameraProvider != null) {
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Выбираем заднюю камеру
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Настройка Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )
            } catch (exc: Exception) {
                tvInfo.text = "Ошибка запуска камеры: ${exc.message}"
                tvInfo.setTextColor(Color.RED)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Метод для освобождения камеры
    private fun releaseCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            cameraExecutor?.shutdown()
            cameraExecutor = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}