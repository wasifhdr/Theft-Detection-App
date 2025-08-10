package com.example.theftdetection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrustedPlaceAdapter(
    private var trustedPlaces: MutableList<TrustedPlace>,
    // --- NEW: A listener for when the whole item is clicked ---
    private val onItemClicked: (TrustedPlace) -> Unit,
    private val onDeleteClicked: (TrustedPlace) -> Unit
) : RecyclerView.Adapter<TrustedPlaceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconImageView: ImageView = view.findViewById(R.id.placeIcon)
        val labelTextView: TextView = view.findViewById(R.id.placeLabel)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_trusted_place, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = trustedPlaces[position]
        holder.labelTextView.text = place.label
        holder.deleteButton.setOnClickListener {
            onDeleteClicked(place)
        }

        // --- NEW: Set a click listener on the entire row ---
        holder.itemView.setOnClickListener {
            onItemClicked(place)
        }

        if (place.type == PlaceType.LOCATION) {
            holder.iconImageView.setImageResource(R.drawable.ic_map)
        } else {
            holder.iconImageView.setImageResource(R.drawable.ic_wifi)
        }
    }

    override fun getItemCount() = trustedPlaces.size

    fun updateList(newPlaces: List<TrustedPlace>) {
        trustedPlaces.clear()
        trustedPlaces.addAll(newPlaces)
        notifyDataSetChanged()
    }
}
