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
 *
 * A partir desta fase, a deduplicação usa [TvDevice.stableKey] em vez do
 * IP - uma TV pareada que mude de IP não gera um item duplicado. Também
 * passa a exibir um selo "Pareada" nos itens cuja chave já está entre as
 * TVs cadastradas ([updatePairedKeys]), sem nunca remover o item da lista
 * por causa disso - a descoberta continua sempre mostrando todas as TVs
 * encontradas na rede, pareadas ou não.
 */
class DeviceListAdapter(
    private val onDeviceSelected: (TvDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<TvDevice>()
    private var selectedPosition = RecyclerView.NO_POSITION
    private var pairedKeys: Set<String> = emptySet()

    fun addDevice(device: TvDevice) {
        if (devices.any { it.stableKey() == device.stableKey() }) return
        devices.add(device)
        notifyItemInserted(devices.size - 1)
    }

    fun clear() {
        devices.clear()
        selectedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = devices.isEmpty()

    /**
     * Atualiza o conjunto de chaves ([TvDevice.stableKey]) das TVs
     * atualmente pareadas, para que os itens já exibidos passem a mostrar
     * (ou deixar de mostrar) o selo "Pareada". Chamar sempre que a lista de
     * pareadas puder ter mudado (ex: ao final de um scan, ou após
     * parear/esquecer uma TV).
     */
    fun updatePairedKeys(keys: Set<String>) {
        pairedKeys = keys
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tv_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device, position == selectedPosition, pairedKeys.contains(device.stableKey())) { pos ->
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

        // Selo "Pareada". Busca de forma segura (findViewById pode retornar
        // null) porque o item_tv_device.xml existente pode ainda não ter
        // esta view - ver observação sobre o layout na entrega desta etapa.
        private val pairedBadge: TextView? = itemView.findViewById(R.id.tvPairedBadge)

        fun bind(device: TvDevice, isSelected: Boolean, isPaired: Boolean, onClick: (Int) -> Unit) {
            name.text = device.name
            val brandText = device.brand ?: itemView.context.getString(R.string.device_unknown_brand)
            subtitle.text = itemView.context.getString(
                R.string.device_subtitle_format, brandText, device.ip, device.protocol.name
            )
            card.isChecked = isSelected
            pairedBadge?.visibility = if (isPaired) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(adapterPosition) }
        }
    }
}
