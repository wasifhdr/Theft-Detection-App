package com.example.theftdetection

import org.osmdroid.util.GeoPoint
import java.io.Serializable

// An enum to clearly define the type of trusted place.
enum class PlaceType {
    LOCATION, WIFI
}

// The updated data class. Note that location and ssid are "nullable" (can be null)
// because a trusted place will only have one or the other.
// It implements Serializable so we can pass it between activities.
data class TrustedPlace(
    val label: String,
    val type: PlaceType,
    val location: GeoPoint? = null, // Only used if type is LOCATION
    val ssid: String? = null,       // Only used if type is WIFI
) : Serializable
