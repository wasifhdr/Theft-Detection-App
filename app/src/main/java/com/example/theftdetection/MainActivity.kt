package com.example.theftdetection

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import android.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var sensitivitySlider: Slider
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var trustedPlacesButton: MaterialButton
    private lateinit var vibrator: Vibrator

    // Launcher for Notification Permission
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Notification permission is needed for the service to work properly.", Toast.LENGTH_LONG).show()
            }
        }

    // Launcher for Location Permissions
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            // This case is for Android 10+ after fine location is granted.
            permissions.getOrDefault(Manifest.permission.ACCESS_BACKGROUND_LOCATION, false) -> {
                // Now that permissions are granted, the user can tap the button again to open the activity.
            }
            // This is the first step: ask for fine location.
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                // Once fine location is granted, ask for background location on newer OS versions.
                requestBackgroundLocationPermission()
            } else -> {
            // The user denied location access.
            Toast.makeText(this, "Location permissions are required for Trusted Places.", Toast.LENGTH_LONG).show()
        }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("TheftDetectionPrefs", Context.MODE_PRIVATE)

        // Find all views
        toggleButton = findViewById(R.id.toggleDetectionButton)
        sensitivitySlider = findViewById(R.id.sensitivitySlider)
        trustedPlacesButton = findViewById(R.id.trustedPlacesButton)

        // Set up slider
        val savedSensitivity = sharedPreferences.getFloat("sensitivityLevel", 3.0f)
        sensitivitySlider.value = savedSensitivity
        // --- MODIFIED: Add haptic feedback to the slider ---
        sensitivitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                performHapticFeedback()
            }
            // This line must be inside the curly braces to see the 'value' variable.
            sharedPreferences.edit().putFloat("sensitivityLevel", value).apply()
        }

        toggleButton.setOnClickListener {
            performDoubleClickHaptic()
            if (isServiceRunning(MotionDetectionService::class.java)) {
                stopMotionService()
            } else {
                startMotionService()
            }
        }

        trustedPlacesButton.setOnClickListener {
            performHapticFeedback()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(this, TrustedPlacesListActivity::class.java)
                startActivity(intent)
            } else {
                requestLocationPermissions()
            }
        }

        // Ask for non-location permissions on startup
        requestDeviceAdmin()
        askNotificationPermission()
    }

    // --- NEW: A helper function to trigger a simple click vibration ---
    private fun performHapticFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use a predefined click effect on modern Android versions
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            // Use a short one-shot vibration for older versions
            @Suppress("DEPRECATION")
            vibrator.vibrate(50) // Vibrate for 50 milliseconds
        }
    }

    private fun performDoubleClickHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use a predefined double click effect on modern Android versions
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            // Use a custom pattern for older versions (vibrate, pause, vibrate)
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 50, 50, 50), -1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    // --- PERMISSION HANDLING ---

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Explain to the user why background location is needed before showing the dialog
            AlertDialog.Builder(this)
                .setTitle("Background Location Required")
                .setMessage("This app uses background location to automatically pause theft detection when you are in a trusted place. Please select 'Allow all the time'.")
                .setPositiveButton("OK") { _, _ ->
                    locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                .show()
        }
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(compName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to lock the device when theft is detected.")
            startActivity(intent)
        }
    }

    // --- SERVICE AND UI LOGIC ---

    private fun startMotionService() {
        val serviceIntent = Intent(this, MotionDetectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        //Toast.makeText(this, "Detection Started", Toast.LENGTH_SHORT).show()
        updateButtonState()
    }

    private fun stopMotionService() {
        val serviceIntent = Intent(this, MotionDetectionService::class.java)
        stopService(serviceIntent)
        //Toast.makeText(this, "Detection Stopped", Toast.LENGTH_SHORT).show()
        updateButtonState()
    }

    private fun updateButtonState() {
        val isRunning = isServiceRunning(MotionDetectionService::class.java)
        if (isRunning) {
            toggleButton.text = "Stop Detection"
            (toggleButton as MaterialButton).setIconResource(R.drawable.ic_lock_lock)
            sensitivitySlider.isEnabled = false
        } else {
            toggleButton.text = "Start Detection"
            (toggleButton as MaterialButton).setIconResource(R.drawable.ic_lock_open)
            sensitivitySlider.isEnabled = true
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
