package com.example.smartremote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.databinding.ItemAppBinding
import com.example.smartremote.model.RemoteKey

/**
 * Adapter simples (sem DI, sem ViewModel - mesmo padrão de simplicidade
 * já usado no resto do projeto) para a grade de apps do AppsBottomSheet.
 *
 * Cards cujo RemoteKey não está em [supportedApps] ficam com alpha
 * reduzido, mas continuam clicáveis - o clique ainda chega em
 * [onAppClick], que decide (na Activity/BottomSheet) se mostra um aviso
 * de "não suportado" ou envia o comando de verdade. Isso evita duplicar
 * essa regra aqui dentro do adapter.
 */
class AppsAdapter(
    private val items: List<AppItem>,
    private val supportedApps: Set<RemoteKey>,
    private val onAppClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private companion object {
        const val ALPHA_SUPPORTED = 1f
        const val ALPHA_UNSUPPORTED = 0.35f
    }

    inner class AppViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = items[position]
        val isSupported = supportedApps.contains(item.key)

        with(holder.binding) {
            appIcon.text = item.iconLetter
            appIcon.setBackgroundResource(item.iconBackgroundRes)
            appLabel.text = item.label
            appCardRoot.alpha = if (isSupported) ALPHA_SUPPORTED else ALPHA_UNSUPPORTED
            appCardRoot.setOnClickListener { onAppClick(item) }
        }
    }

    override fun getItemCount(): Int = items.size
}
