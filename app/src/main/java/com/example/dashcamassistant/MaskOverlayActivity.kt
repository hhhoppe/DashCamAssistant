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

// Обработчик экрана показа маски
class MaskOverlayActivity : AppCompatActivity() {

    private lateinit var ivMask: ImageView          // Картинка с маской
    private lateinit var seekBarAlpha: SeekBar      // Ползунок прозрачности
    private lateinit var tvAlpha: TextView          // Текущая прозрачность
    private lateinit var btnClose: Button           // Закрыть
    private lateinit var btnToggleOverlay: Button   // Показать/скрыть маску
    private lateinit var tvInfo: TextView           // Состояние маски
    private lateinit var previewView: androidx.camera.view.PreviewView  // Картинка с камеры

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var originalMask: Bitmap? = null    // Маска
    private var isOverlayVisible = true         // Статус показа маски

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mask_overlay)

        // Убираем статус бар, чтобы изображение было на весь экран
        WindowCompat.setDecorFitsSystemWindows(window, false)

        initViews()
        loadMask()
        setupListeners()
        startCamera()
    }

    // Перезапуск камеры
    override fun onResume() {
        super.onResume()
        if (cameraProvider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    // Освобождение камеры
    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
        originalMask?.recycle()
    }

    // Находим все элементы интерфейса по их ID в layout-файле
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

    // Метод для получения маски из файла и вывод на экран
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

            // Выводим состояние маски
            tvInfo.text = "Маска загружена\nБелые зоны - игнорируются\nЧёрные зоны - анализируются"
            tvInfo.setTextColor(Color.GREEN)
        } else {
            // Сообщаем об ошибке и отключаем управление
            ivMask.setImageBitmap(null)
            tvInfo.text = "Нет сохранённой маски\nСначала выполните калибровку"
            tvInfo.setTextColor(Color.RED)
            seekBarAlpha.isEnabled = false
            btnToggleOverlay.isEnabled = false
        }
    }

    // Метод для поворота маски под текущую ориентацию телефона
    private fun rotateMaskForDisplay(mask: Bitmap): Bitmap {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Определяем текущую ориентацию экрана (вертикальная)
        val isPortrait = screenHeight > screenWidth

        // Маска из калибровки всегда в горизонтальной ориентации
        val isMaskLandscape = mask.width > mask.height

        // Подгоняем маску под экран
        return when {
            isPortrait && isMaskLandscape -> {
                Bitmap.createScaledBitmap(mask, screenWidth, screenHeight, true)
            }
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

    // Обработчики для кнопок
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

    // Метод для показа камеры
    private fun startCamera() {
        // Не запускаем если уже запущена
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