package com.example.smartremote.faq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smartremote.R
import com.example.smartremote.databinding.ItemFaqBinding

/**
 * Adapter simples (sem seleção múltipla, sem DiffUtil - só 6 itens fixos)
 * para a lista expansível de FAQ. Cada toque no card chama [onToggle] com
 * a posição tocada; quem decide COMO alternar o estado (um item por vez
 * ou vários simultâneos) é a [FaqActivity], não o adapter - o adapter só
 * renderiza o que recebe em [submitList].
 */
class FaqAdapter(
    private val onToggle: (position: Int) -> Unit
) : RecyclerView.Adapter<FaqAdapter.FaqViewHolder>() {

    private val items = mutableListOf<FaqItem>()

    fun submitList(newItems: List<FaqItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaqViewHolder {
        val binding = ItemFaqBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FaqViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FaqViewHolder, position: Int) {
        holder.bind(items[position]) { onToggle(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class FaqViewHolder(private val binding: ItemFaqBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FaqItem, onClick: () -> Unit) {
            binding.txtFaqQuestion.text = item.question
            binding.txtFaqAnswer.text = item.answer
            binding.txtFaqAnswer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            binding.imgFaqChevron.setImageResource(
                if (item.isExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
            binding.layoutFaqItem.setOnClickListener { onClick() }
        }
    }
}
