package com.example.theftdetection

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TrustedPlacesListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var addWifiButton: Button
    private lateinit var addMapLocationButton: Button
    private lateinit var adapter: TrustedPlaceAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var vibrator: Vibrator

    private var allTrustedPlaces: MutableList<TrustedPlace> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trusted_places_list)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sharedPreferences = getSharedPreferences("TheftDetectionPrefs", Context.MODE_PRIVATE)
        recyclerView = findViewById(R.id.trustedPlacesRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        addWifiButton = findViewById(R.id.addWifiButton)
        addMapLocationButton = findViewById(R.id.addMapLocationButton)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        tabLayout = findViewById(R.id.tabLayout)

        setupRecyclerView(mutableListOf())

        swipeRefreshLayout.setOnRefreshListener {
            loadAllTrustedPlaces()
            filterListBySelectedTab()
            swipeRefreshLayout.isRefreshing = false
        }

        addMapLocationButton.setOnClickListener {
            performHapticFeedback()
            val intent = Intent(this, MapSelectionActivity::class.java)
            startActivity(intent)
        }

        addWifiButton.setOnClickListener {
            performHapticFeedback()
            addCurrentWifi()
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                filterListBySelectedTab()
                updateButtonVisibility()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
        loadAllTrustedPlaces()
        filterListBySelectedTab()
        updateButtonVisibility()
    }

    private fun updateButtonVisibility() {
        val selectedTabPosition = tabLayout.selectedTabPosition
        if (selectedTabPosition == 0) { // Locations tab
            addMapLocationButton.visibility = View.VISIBLE
            addWifiButton.visibility = View.GONE
        } else { // Wi-Fi tab
            addMapLocationButton.visibility = View.GONE
            addWifiButton.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView(places: MutableList<TrustedPlace>) {
        adapter = TrustedPlaceAdapter(
            trustedPlaces = places,
            // --- NEW: Handle item click ---
            onItemClicked = { placeToRename ->
                // Only allow renaming for location-based places
                if (placeToRename.type == PlaceType.LOCATION) {
                    showRenameDialog(placeToRename)
                } else {
                    Toast.makeText(this, "Wi-Fi names cannot be changed.", Toast.LENGTH_SHORT).show()
                }
            },
            // Handle delete click
            onDeleteClicked = { placeToDelete ->
                showDeleteConfirmationDialog(placeToDelete)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    // --- NEW: Function to show the rename dialog ---
    private fun showRenameDialog(placeToRename: TrustedPlace) {
        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_place, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.placeLabelInput)

        // Pre-fill the input with the current label
        labelInput.setText(placeToRename.label)

        builder.setView(dialogView)
            .setTitle("Rename Trusted Place")
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString()
                if (newLabel.isNotBlank()) {
                    // Find the item in the master list and update it
                    val index = allTrustedPlaces.indexOf(placeToRename)
                    if (index != -1) {
                        allTrustedPlaces[index] = placeToRename.copy(label = newLabel)
                        saveTrustedPlaces()
                        filterListBySelectedTab() // Refresh the UI
                    }
                } else {
                    Toast.makeText(this, "Label cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun filterListBySelectedTab() {
        val selectedTabPosition = tabLayout.selectedTabPosition
        val filteredList = if (selectedTabPosition == 0) {
            allTrustedPlaces.filter { it.type == PlaceType.LOCATION }
        } else {
            allTrustedPlaces.filter { it.type == PlaceType.WIFI }
        }
        updateUI(filteredList)
    }

    private fun showDeleteConfirmationDialog(place: TrustedPlace) {
        AlertDialog.Builder(this)
            .setTitle("Delete Place")
            .setMessage("Are you sure you want to delete '${place.label}'?")
            .setPositiveButton("Delete") { _, _ ->
                allTrustedPlaces.remove(place)
                saveTrustedPlaces()
                filterListBySelectedTab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addCurrentWifi() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Wi-Fi is not enabled.", Toast.LENGTH_SHORT).show()
            return
        }
        val connectionInfo = wifiManager.connectionInfo
        val ssid = connectionInfo.ssid.replace("\"", "")

        if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
            val newPlace = TrustedPlace(label = ssid, type = PlaceType.WIFI, ssid = ssid)
            allTrustedPlaces.add(newPlace)
            saveTrustedPlaces()
            filterListBySelectedTab()
            Toast.makeText(this, "'$ssid' added to trusted Wi-Fi networks.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Not connected to a Wi-Fi network.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAllTrustedPlaces() {
        val json = sharedPreferences.getString("trustedPlacesWithLabels", null)
        allTrustedPlaces.clear()
        if (json != null) {
            val type = object : TypeToken<MutableList<TrustedPlace>>() {}.type
            allTrustedPlaces.addAll(gson.fromJson(json, type))
        }
    }

    private fun saveTrustedPlaces() {
        val json = gson.toJson(allTrustedPlaces)
        sharedPreferences.edit().putString("trustedPlacesWithLabels", json).apply()
    }

    private fun updateUI(listToDisplay: List<TrustedPlace>) {
        adapter.updateList(listToDisplay)
        if (listToDisplay.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }
}
