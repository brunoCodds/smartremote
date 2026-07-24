package com.example.smartremote.discovery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.R
import com.example.smartremote.model.TvDevice
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Lista de TVs pareadas (múltiplas, ao contrário do card único que existia
 * antes). Cada item é independente: tocar no cabeçalho revela suas
 * próprias ações (Esquecer / Conectar ou Desconectar, dependendo se esta é
 * a TV atualmente conectada) - exatamente como o card único fazia, só que
 * agora por TV.
 *
 * Esquecer uma TV aqui nunca afeta as demais (ver TvManager.forgetDevice).
 */
class PairedDeviceListAdapter(
    private val onConnect: (TvDevice) -> Unit,
    private val onDisconnect: (TvDevice) -> Unit,
    private val onForget: (TvDevice) -> Unit
) : RecyclerView.Adapter<PairedDeviceListAdapter.PairedDeviceViewHolder>() {

    private val devices = mutableListOf<TvDevice>()
    private val expandedKeys = mutableSetOf<String>()
    private var connectedKey: String? = null

    /** Substitui a lista inteira de TVs pareadas exibidas. */
    fun submitList(newDevices: List<TvDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    /** Atualiza qual TV (por stableKey) é a atualmente conectada, ou null se nenhuma. */
    fun updateConnectedKey(key: String?) {
        connectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PairedDeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_paired_device, parent, false)
        return PairedDeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PairedDeviceViewHolder, position: Int) {
        val device = devices[position]
        val key = device.stableKey()
        holder.bind(
            device = device,
            isConnected = key == connectedKey,
            isExpanded = expandedKeys.contains(key),
            onToggleExpand = {
                if (!expandedKeys.add(key)) expandedKeys.remove(key)
                notifyItemChanged(position)
            },
            onConnect = { onConnect(device) },
            onDisconnect = { onDisconnect(device) },
            onForget = { onForget(device) }
        )
    }

    override fun getItemCount(): Int = devices.size

    class PairedDeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardPairedDevice)
        private val name: TextView = itemView.findViewById(R.id.tvPairedDeviceName)
        private val subtitle: TextView = itemView.findViewById(R.id.tvPairedDeviceSubtitle)
        private val connectedBadge: TextView = itemView.findViewById(R.id.tvPairedConnectedBadge)
        private val actionsLayout: View = itemView.findViewById(R.id.layoutPairedDeviceActions)
        private val btnForget: MaterialButton = itemView.findViewById(R.id.btnForgetPairedDevice)
        private val btnConnectOrDisconnect: MaterialButton =
            itemView.findViewById(R.id.btnConnectOrDisconnectPairedDevice)

        fun bind(
            device: TvDevice,
            isConnected: Boolean,
            isExpanded: Boolean,
            onToggleExpand: () -> Unit,
            onConnect: () -> Unit,
            onDisconnect: () -> Unit,
            onForget: () -> Unit
        ) {
            name.text = device.name
            val brandText = device.brand ?: itemView.context.getString(R.string.device_unknown_brand)
            subtitle.text = itemView.context.getString(
                R.string.device_subtitle_format, brandText, device.ip, device.protocol.name
            )

            connectedBadge.visibility = if (isConnected) View.VISIBLE else View.GONE
            actionsLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE

            btnConnectOrDisconnect.text = itemView.context.getString(
                if (isConnected) R.string.discovery_disconnect_button else R.string.discovery_connect_button
            )
            btnConnectOrDisconnect.setOnClickListener {
                if (isConnected) onDisconnect() else onConnect()
            }
            btnForget.setOnClickListener { onForget() }

            itemView.setOnClickListener { onToggleExpand() }
            card.setOnClickListener { onToggleExpand() }
        }
    }
}
