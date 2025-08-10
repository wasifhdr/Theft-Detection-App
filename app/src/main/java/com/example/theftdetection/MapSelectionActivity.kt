package com.example.theftdetection

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapSelectionActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var addPlaceButton: FloatingActionButton
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var vibrator: Vibrator
    private val gson = Gson()

    // --- MODIFIED: These variables now track the actively selected point ---
    private var selectedPoint: GeoPoint? = null
    private var selectionMarker: Marker? = null
    private var selectionCircle: Polygon? = null

    private lateinit var locationOverlay: MyLocationNewOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        setContentView(R.layout.activity_trusted_places)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sharedPreferences = getSharedPreferences("TheftDetectionPrefs", Context.MODE_PRIVATE)
        mapView = findViewById(R.id.map)
        addPlaceButton = findViewById(R.id.addPlaceButton)

        setupMap()
        // --- NEW: Re-introducing the tap listener ---
        setupMapListeners()

        // --- MODIFIED: The "+" button now uses the dynamically selected point ---
        addPlaceButton.setOnClickListener {
            performHapticFeedback()
            selectedPoint?.let { point ->
                showAddPlaceDialog(point)
            } ?: run {
                Toast.makeText(this, "Could not determine location. Please wait or tap on the map.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(19.0)

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        mapView.overlays.add(locationOverlay)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(this).lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val userLocation = GeoPoint(location.latitude, location.longitude)
                    // --- NEW: Set the user's current location as the initial selection ---
                    selectedPoint = userLocation
                    mapView.controller.animateTo(userLocation)
                    drawSelectionVisuals(userLocation)
                } else {
                    Toast.makeText(this, "Could not get initial location.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- NEW: This function sets up the listener for single taps ---
    private fun setupMapListeners() {
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let {
                    // When the user taps, update the selected point and move the visuals.
                    selectedPoint = it
                    drawSelectionVisuals(it)
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false // Long press is not used
            }
        }
        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(0, mapEventsOverlay) // Add it at the bottom of the overlay stack
    }

    // --- RENAMED & MODIFIED: This function now draws visuals for any selected point ---
    private fun drawSelectionVisuals(point: GeoPoint) {
        // If visuals already exist, remove them first.
        selectionMarker?.let { mapView.overlays.remove(it) }
        selectionCircle?.let { mapView.overlays.remove(it) }

        // Create a new marker for the selected spot.
        selectionMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Selected Location"
        }

        // Create a new circle for the selected spot.
        selectionCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(point, 50.0) // 100-meter radius
            fillColor = 0x220000FF
            strokeColor = 0x880000FF.toInt()
        }

        mapView.overlays.add(selectionMarker)
        mapView.overlays.add(selectionCircle)
        mapView.invalidate() // Redraw the map
    }

    private fun showAddPlaceDialog(point: GeoPoint) {
        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_place, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.placeLabelInput)

        builder.setView(dialogView)
            .setTitle("Add Trusted Place")
            .setPositiveButton("Save") { _, _ ->
                val label = labelInput.text.toString()
                if (label.isNotBlank()) {
                    saveNewPlace(label, point)
                    finish()
                } else {
                    Toast.makeText(this, "Label cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    private fun saveNewPlace(label: String, location: GeoPoint) {
        val json = sharedPreferences.getString("trustedPlacesWithLabels", null)
        val type = object : TypeToken<MutableList<TrustedPlace>>() {}.type
        val trustedPlaces: MutableList<TrustedPlace> = if (json != null) {
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }

        val newPlace = TrustedPlace(label = label, type = PlaceType.LOCATION, location = location)
        trustedPlaces.add(newPlace)

        val newJson = gson.toJson(trustedPlaces)
        sharedPreferences.edit().putString("trustedPlacesWithLabels", newJson).commit()
        Toast.makeText(this, "Trusted place saved!", Toast.LENGTH_SHORT).show()
    }

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

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        locationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationOverlay.disableMyLocation()
    }
}
