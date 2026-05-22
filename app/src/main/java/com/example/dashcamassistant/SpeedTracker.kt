package com.example.dashcamassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

// Класс для получения скорости ТС через GPS
class SpeedTracker(
    private val context: Context,
    private val onSpeedChanged: (Float) -> Unit
) : LocationListener {

    companion object {
        private const val TAG = "SpeedTracker"
        private const val SPEED_THRESHOLD = 5f // Порог движения ТС
    }

    private var locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var currentSpeed = 0f   // Текущая скорость
    var isMoving = false            // ТС движется
        private set

    // Метод для получения данных
    fun start() {
        // Если есть разрешение запрашиваем обновления с GPS
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000, // раз в сек
                1f, // 1 метр минимум
                this
            )
            // Или с сетевого провайдера
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000, // раз в сек
                    1f, // 1 метр минимум
                    this
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Ошибка разрешения на определение местоположения по сети: ${e.message}")
            }
            Log.d(TAG, "SpeedTracker начал работу")
        } else {
            Log.d(TAG, "Нет разрешения на доступ к местоположению")
        }
    }

    // Метод для остановки получения данных
    fun stop() {
        locationManager.removeUpdates(this)
        Log.d(TAG, "SpeedTracker закончил работу")
    }

    // Метод для обработки полученных данных
    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location: lat=${location.latitude}, lon=${location.longitude}, hasSpeed=${location.hasSpeed()}, speed=${location.speed}")

        // Если нет скорости в точке - пропускаем
        if (!location.hasSpeed()) {
            Log.d(TAG, "Скорость недоступна")
            return
        }

        // Если скорость изменилась вызываем callback и сохраняем
        val speed = location.speed * 3.6f // из м/с в км/ч
        if (speed != currentSpeed) {
            currentSpeed = speed
            isMoving = speed > SPEED_THRESHOLD
            onSpeedChanged(currentSpeed)
            Log.d(TAG, "Speed: ${"%.1f".format(speed)} km/h, isMoving=$isMoving")
        }
    }

    // Методы для LocationListener
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}