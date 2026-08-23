package com.example.smartremote.discovery

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * *** NOVO - v0.9.3, item 4 (Diagnóstico Aprofundado) ***
 *
 * Lista CRUA (sem nenhum tratamento de mensagem, ao contrário do painel
 * simples) dos eventos de [DiscoveryDiagnostics] - nome técnico do
 * [DiscoveryEventType], nome do scanner e timestamp com precisão de
 * milissegundos (não o "HH:mm:ss" do [com.example.smartremote.diagnostic.DiagnosticLogEntry],
 * que é o suficiente para o painel simples mas não para investigar
 * ordenação exata de eventos quase simultâneos).
 *
 * Reaproveita o layout item_diagnostic_log.xml (só um TextView
 * monoespaçado) - não faz sentido duplicar esse XML só porque a origem do
 * dado é outra classe.
 */
class DiscoveryEventAdapter : RecyclerView.Adapter<DiscoveryEventAdapter.ViewHolder>() {

    private val events = mutableListOf<DiscoveryEvent>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun submitList(newEvents: List<DiscoveryEvent>) {
        events.clear()
        events.addAll(newEvents)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diagnostic_log, parent, false)
        return ViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(events[position], timeFormat)
    }

    override fun getItemCount(): Int = events.size

    class ViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {

        fun bind(event: DiscoveryEvent, timeFormat: SimpleDateFormat) {
            val time = timeFormat.format(event.timestampMs)
            val detailSuffix = if (event.detail.isNotBlank()) " - ${event.detail}" else ""
            textView.text = "[$time] ${event.scannerName} ${event.type}$detailSuffix"
            textView.setTextColor(colorForType(event.type, textView.context))
        }

        /**
         * Mesmo espírito do mapeamento em
         * [com.example.smartremote.diagnostic.DiagnosticLogAdapter], mas
         * para [DiscoveryEventType]: SCAN_ERROR/DEVICE_DISCARDED são os
         * dois únicos tipos que sinalizam algo "deu errado ou foi
         * rejeitado" (por isso danger/warning) - o resto é o fluxo normal
         * de uma descoberta bem-sucedida, então usa as cores mais neutras
         * já usadas para eventos de rede/transporte (colorAccentDim) ou
         * marcos de sucesso (colorSuccess/colorAccent).
         */
        private fun colorForType(type: DiscoveryEventType, context: Context): Int {
            val attr = when (type) {
                DiscoveryEventType.SCAN_ERROR -> R.attr.colorDanger
                DiscoveryEventType.DEVICE_DISCARDED -> R.attr.colorWarning
                DiscoveryEventType.DEVICE_CREATED, DiscoveryEventType.DEVICE_FORWARDED -> R.attr.colorSuccess
                DiscoveryEventType.DEVICE_UPDATED -> R.attr.colorAccent
                DiscoveryEventType.SCAN_STARTED, DiscoveryEventType.SCAN_FINISHED -> R.attr.colorTextSecondary
                DiscoveryEventType.REQUEST_SENT,
                DiscoveryEventType.RESPONSE_RECEIVED,
                DiscoveryEventType.PAYLOAD_RECEIVED,
                DiscoveryEventType.PAYLOAD_PARSED -> R.attr.colorAccentDim
            }
            val typedValue = TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}
