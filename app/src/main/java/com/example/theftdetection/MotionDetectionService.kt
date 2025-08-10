package com.example.theftdetection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.sqrt
import android.content.BroadcastReceiver
import android.content.IntentFilter

class MotionDetectionService : Service(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var movementThreshold = 50.0f
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var trustedPlaces: List<TrustedPlace> = emptyList()
    private var isProtectionPaused = false
    private val TRUSTED_RADIUS_METERS = 50.0
    private var isScreenOn = true
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "MotionDetectionChannel"
        const val NOTIFICATION_ID = 1
        const val TAG = "MotionDetectionService"
        const val ACTION_STOP_SERVICE = "com.example.theftdetection.ACTION_STOP_SERVICE"
    }

    private val screenStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    // This is sent when the user unlocks the phone.
                    isScreenOn = true
                    Log.d(TAG, "Screen unlocked. Motion detection is active.")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    Log.d(TAG, "Screen turned off. Motion detection is paused.")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sharedPreferences = getSharedPreferences("TheftDetectionPrefs", Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, intentFilter)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "trustedPlacesWithLabels") {
            Log.d(TAG, "Trusted places list has changed. Reloading and re-checking.")
            loadTrustedPlaces()
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        checkCurrentState(it)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        calculateThreshold()
        loadTrustedPlaces()
        startLocationUpdates()
        startForeground(NOTIFICATION_ID, createNotification())
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return START_STICKY
    }

    private fun loadTrustedPlaces() {
        val json = sharedPreferences.getString("trustedPlacesWithLabels", null)
        if (json != null) {
            val type = object : TypeToken<List<TrustedPlace>>() {}.type
            trustedPlaces = gson.fromJson(json, type)
        } else {
            trustedPlaces = emptyList()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                checkCurrentState(location)
            }
        }
    }

    private fun checkCurrentState(currentLocation: Location) {
        val wasPaused = isProtectionPaused
        val isLocationTrusted = isInsideTrustedLocation(currentLocation)
        val isWifiTrusted = isConnectedToTrustedWifi()
        isProtectionPaused = isLocationTrusted || isWifiTrusted
        if (!wasPaused && isProtectionPaused) {
            Log.d(TAG, "Protection paused (trusted location or Wi-Fi).")
        } else if (wasPaused && !isProtectionPaused) {
            Log.d(TAG, "Protection resumed (left trusted area).")
        }
    }

    private fun isInsideTrustedLocation(currentLocation: Location): Boolean {
        val locationPlaces = trustedPlaces.filter { it.type == PlaceType.LOCATION }
        if (locationPlaces.isEmpty()) return false
        for (place in locationPlaces) {
            val trustedGeoPoint = place.location!!
            val trustedLocation = Location("").apply {
                latitude = trustedGeoPoint.latitude
                longitude = trustedGeoPoint.longitude
            }
            if (currentLocation.distanceTo(trustedLocation) < TRUSTED_RADIUS_METERS) {
                return true
            }
        }
        return false
    }

    private fun isConnectedToTrustedWifi(): Boolean {
        val wifiPlaces = trustedPlaces.filter { it.type == PlaceType.WIFI }
        if (wifiPlaces.isEmpty()) return false
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            val currentSsid = wifiManager.connectionInfo.ssid.replace("\"", "")
            return wifiPlaces.any { it.ssid == currentSsid }
        }
        return false
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60000).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isProtectionPaused || !isScreenOn) {
            return
        }
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (acceleration > movementThreshold) {
                lockDevice()
            }
        }
    }

    private fun calculateThreshold() {
        val sensitivityLevel = sharedPreferences.getFloat("sensitivityLevel", 3.0f)
        movementThreshold = 80.0f - (sensitivityLevel - 1.0f) * 15.0f
    }

    private fun lockDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(compName)) {
            dpm.lockNow()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(screenStateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Motion Detection Service Channel", NotificationManager.IMPORTANCE_MIN)
            serviceChannel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopServiceIntent = Intent(this, MotionDetectionService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopServicePendingIntent = PendingIntent.getService(this, 0, stopServiceIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Protection Enabled")
            .setContentText("Motion detection is active.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Detection", stopServicePendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setNotificationSilent()
            .build()
    }
}
