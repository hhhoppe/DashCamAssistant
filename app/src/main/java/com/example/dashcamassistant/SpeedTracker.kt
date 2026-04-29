package com.example.dashcamassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

class SpeedTracker(
    private val context: Context,
    private val onSpeedChanged: (Float) -> Unit
) : LocationListener {

    companion object {
        private const val TAG = "SpeedTracker"
        private const val SPEED_THRESHOLD = 5f
    }

    private var locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var currentSpeed = 0f
    var isMoving = false
        private set

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                1f,
                this
            )
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000,
                    1f,
                    this
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Network location permission error: ${e.message}")
            }
            Log.d(TAG, "SpeedTracker started")
        } else {
            Log.d(TAG, "No location permission")
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
        Log.d(TAG, "SpeedTracker stopped")
    }

    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location: lat=${location.latitude}, lon=${location.longitude}, hasSpeed=${location.hasSpeed()}, speed=${location.speed}")

        if (!location.hasSpeed()) {
            Log.d(TAG, "Speed not available")
            return
        }

        val speed = location.speed * 3.6f
        if (speed != currentSpeed) {
            currentSpeed = speed
            isMoving = speed > SPEED_THRESHOLD
            onSpeedChanged(currentSpeed)
            Log.d(TAG, "Speed: ${"%.1f".format(speed)} km/h, isMoving=$isMoving")
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}