package com.example.dashcamassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.dashcamassistant.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import android.util.Log
import android.os.Looper
import android.os.Handler
import android.content.Intent

// Обработчик главного экрана
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var visionAnalyzer: VisionAnalyzer? = null
    private var speedTracker: SpeedTracker? = null

    private var isCarMoving = false
    private var isCalibrationRequested = false // Флаг для запроса калибровки
    private var isCameraStarting = false

    // Необходимые разрешения
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // Запрос разрешений
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startCamera()
            startSpeedTracker()
        } else {
            Toast.makeText(this, "Необходимы все разрешения для работы", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убираем стандартные отступы системы
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Проверяем, если мы пришли ли из настроек с запросом на калибровку
        isCalibrationRequested = intent.getBooleanExtra("start_calibration", false)
        Log.d("MainActivity", "isCalibrationRequested = $isCalibrationRequested")

        // Получаем высоту статус-бара
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0

        // Применяем отступ к тексту со скоростью
        val params = binding.tvSpeed.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.topMargin = statusBarHeight
        binding.tvSpeed.layoutParams = params

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Проверяем разрешения
        if (checkPermissions()) {
            startCamera()
            startSpeedTracker()
        } else {
            requestPermissions()
        }

        // Настройка кнопок
        binding.btnStartRecording.setOnClickListener {
            startRecording()
        }

        binding.btnStopRecording.setOnClickListener {
            stopRecording()
        }

        binding.btnSettings.setOnClickListener {
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Чтобы экран не уходил в сон
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()

        // перезапускаем камеру если есть разрешения и она не запущена
        if (checkPermissions() && !isCameraStarting) {
            startCamera()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val calibrationRequested = intent.getBooleanExtra("start_calibration", false)
        Log.d("MainActivity", "onNewIntent: start_calibration = $calibrationRequested")

        if (calibrationRequested) {
            startCalibrationAfterCameraReady()
        }
    }

    private fun startCalibrationAfterCameraReady() {
        // Если анализатор уже создан, запускаем калибровку
        if (visionAnalyzer != null) {
            Log.d("MainActivity", "Запуск калибровки сразу")
            startCalibration()
        } else {
            // Иначе сохраняем флаг, калибровка запустится в startCamera()
            isCalibrationRequested = true
            Log.d("MainActivity", "Калибровка отложена, visionAnalyzer ещё не создан")
        }
    }

    private fun startCalibration() {
        Toast.makeText(this, "Калибровка началась, езжайте в обычном режиме 30 секунд", Toast.LENGTH_LONG).show()
        visionAnalyzer?.startCalibration { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Калибровка завершена!", Toast.LENGTH_LONG).show()
                    val calibrationHelper = CalibrationHelper(this)
                    visionAnalyzer?.getMaskBitmap()?.let { mask ->
                        calibrationHelper.saveMask(mask)
                    }
                } else {
                    Toast.makeText(this, "Ошибка калибровки", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    private fun startSpeedTracker() {
        speedTracker = SpeedTracker(this) { speed ->
            runOnUiThread {
                isCarMoving = speed > 5f
                val speedText = String.format(Locale.getDefault(), "Скорость: %.0f км/ч", speed)
                binding.tvSpeed.text = speedText
                binding.tvSpeed.visibility = android.view.View.VISIBLE
            }
        }
        speedTracker?.start()
    }

    private fun startCamera() {
        if (isCameraStarting) return
        isCameraStarting = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Выбираем заднюю камеру
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Настройка Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            // Настройка Recorder для видео
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            if (imageAnalysis == null) {
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
            }

            // Передаём в анализатор флаг движения автомобиля
            if (visionAnalyzer == null) {
                visionAnalyzer = VisionAnalyzer(this, { isCarMoving }) {
                    runOnUiThread {
                        showMovementWarning()
                    }
                }
            }
            imageAnalysis?.setAnalyzer(cameraExecutor, visionAnalyzer!!)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    videoCapture
                )

                // Запускаем калибровку после того, как bindToLifecycle успешно выполнился
                if (isCalibrationRequested) {
                    Log.d("MainActivity", "Запуск калибровки после привязки камеры")

                    // Если есть отложенный запрос калибровки, запускаем её
                    if (isCalibrationRequested) {
                        Log.d("MainActivity", "Запуск отложенной калибровки после привязки камеры")
                        isCalibrationRequested = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            startCalibration()
                        }, 2000) // 2 сек
                    }
                }
                isCameraStarting = false
            } catch (exc: Exception) {
                Toast.makeText(this, "Ошибка запуска камеры: ${exc.message}", Toast.LENGTH_SHORT).show()
                isCameraStarting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val videoCapture = videoCapture ?: return

        // Проверяем разрешение на запись
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на запись звука", Toast.LENGTH_SHORT).show()
            return
        }

        // Создаём папку для видео, если её нет
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dashCamDir = File(moviesDir, "DashCamAssistant")
        if (!dashCamDir.exists()) {
            dashCamDir.mkdirs()
        }

        // Создаём файл для видео
        val videoFile = File(dashCamDir, generateFilename())

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.btnStartRecording.isEnabled = false
                        binding.btnStopRecording.isEnabled = true
                        Toast.makeText(this, "Запись начата", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        binding.btnStartRecording.isEnabled = true
                        binding.btnStopRecording.isEnabled = false
                        if (event.hasError()) {
                            Toast.makeText(this, "Ошибка записи", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Видео сохранено", Toast.LENGTH_SHORT).show()
                        }
                        recording = null
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        binding.btnStartRecording.isEnabled = true
        binding.btnStopRecording.isEnabled = false
        Toast.makeText(this, "Запись остановлена", Toast.LENGTH_SHORT).show()
    }

    private fun generateFilename(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return "DASH_${sdf.format(Date())}.mp4"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        visionAnalyzer?.release()
        speedTracker?.stop()
    }

    private fun showMovementWarning() {
        binding.tvWarning.text = "Машина впереди начала движение"
        binding.tvWarning.visibility = android.view.View.VISIBLE
        binding.root.postDelayed({
            binding.tvWarning.visibility = android.view.View.GONE
        }, 2000) // 2 сек
    }
}