package com.example.smartremote.diagnostic

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.R

/**
 * *** NOVO - v0.9.3, item 2 ***
 *
 * Adapter simples (sem seleção, sem clique) para a lista de
 * [DiagnosticLogEntry] do painel de diagnóstico SIMPLES (o do ícone "i" na
 * tela principal - não confundir com o Diagnóstico Aprofundado do item 4,
 * que tem sua própria apresentação sobre a mesma fonte de dados).
 *
 * Reaproveita o padrão de adapter já usado no projeto (ver
 * discovery/DeviceListAdapter) - inflar um item_*.xml simples e colorir/
 * preencher em [DiagnosticLogViewHolder.bind]. Chamado com a lista COMPLETA
 * a cada atualização do DiagnosticManager (nunca mais que
 * [DiagnosticManager] guarda no histórico - hoje 100 entradas), então
 * [notifyDataSetChanged] é aceitável aqui sem DiffUtil: mesmo espírito de
 * simplicidade do resto do projeto (sem LiveData/Flow), e o painel simples
 * não é o lugar de otimizar para milhares de itens - esse é o Diagnóstico
 * Aprofundado (item 4), que tem seu próprio volume de dados.
 */
class DiagnosticLogAdapter : RecyclerView.Adapter<DiagnosticLogAdapter.DiagnosticLogViewHolder>() {

    private val entries = mutableListOf<DiagnosticLogEntry>()

    /** Substitui a lista inteira de entradas exibidas e notifica o RecyclerView. */
    fun submitList(newEntries: List<DiagnosticLogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiagnosticLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diagnostic_log, parent, false)
        return DiagnosticLogViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: DiagnosticLogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class DiagnosticLogViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {

        fun bind(entry: DiagnosticLogEntry) {
            textView.text = textView.context.getString(
                R.string.diagnostic_log_line_format,
                entry.timestamp,
                entry.message
            )
            textView.setTextColor(colorForType(entry.type, textView.context))
        }

        /**
         * Mapeamento de [DiagnosticLogType] para os atributos semânticos já
         * existentes (?attr/colorXxx) sempre que fizer sentido - só
         * colorWarning foi criado especificamente para este item, porque
         * não existia nenhum atributo equivalente antes.
         *
         * - ERROR -> colorDanger (já usado em toda ação destrutiva/de perigo do app)
         * - RESPONSE -> colorSuccess (resposta = confirmação de que algo funcionou)
         * - COMMAND -> colorAccent (ação disparada pelo usuário, cor de destaque do tema)
         * - WARNING -> colorWarning (novo - ver attrs.xml/themes.xml)
         * - NETWORK -> colorAccentDim (evento de transporte/rede - relacionado
         *   à conexão, mas mais discreto que um COMMAND explícito do usuário)
         * - INFO -> colorTextSecondary (informativo neutro, mesmo tom do resto da UI secundária)
         */
        private fun colorForType(type: DiagnosticLogType, context: Context): Int {
            val attr = when (type) {
                DiagnosticLogType.ERROR -> R.attr.colorDanger
                DiagnosticLogType.RESPONSE -> R.attr.colorSuccess
                DiagnosticLogType.COMMAND -> R.attr.colorAccent
                DiagnosticLogType.WARNING -> R.attr.colorWarning
                DiagnosticLogType.NETWORK -> R.attr.colorAccentDim
                DiagnosticLogType.INFO -> R.attr.colorTextSecondary
            }
            val typedValue = TypedValue()
            context.theme.resolveAttribute(attr, typedValue, true)
            return typedValue.data
        }
    }
}
