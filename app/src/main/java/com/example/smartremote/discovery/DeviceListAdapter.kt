package com.example.smartremote.discovery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.R
import com.example.smartremote.model.TvDevice
import com.google.android.material.card.MaterialCardView

/**
 * Adapter simples de seleção única para a lista de TVs encontradas.
 */
class DeviceListAdapter(
    private val onDeviceSelected: (TvDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<TvDevice>()
    private var selectedPosition = RecyclerView.NO_POSITION

    fun addDevice(device: TvDevice) {
        if (devices.any { it.ip == device.ip }) return
        devices.add(device)
        notifyItemInserted(devices.size - 1)
    }

    fun clear() {
        devices.clear()
        selectedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = devices.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tv_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position], position == selectedPosition) { pos ->
            val previous = selectedPosition
            selectedPosition = pos
            notifyItemChanged(previous)
            notifyItemChanged(pos)
            onDeviceSelected(devices[pos])
        }
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardDevice)
        private val name: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val subtitle: TextView = itemView.findViewById(R.id.tvDeviceSubtitle)

        fun bind(device: TvDevice, isSelected: Boolean, onClick: (Int) -> Unit) {
            name.text = device.name
            val brandText = device.brand ?: itemView.context.getString(R.string.device_unknown_brand)
            subtitle.text = itemView.context.getString(
                R.string.device_subtitle_format, brandText, device.ip, device.protocol.name
            )
            card.isChecked = isSelected
            itemView.setOnClickListener { onClick(adapterPosition) }
        }
    }
}
