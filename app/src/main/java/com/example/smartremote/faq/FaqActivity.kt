package com.example.smartremote.faq

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartremote.R
import com.example.smartremote.databinding.ActivityFaqBinding

/**
 * *** NOVO - v0.9.3, item 3 ***
 *
 * Tela de perguntas frequentes, acessada pelo menu lateral. Conteúdo
 * fixo (as 6 perguntas/respostas definitivas do escopo desta versão -
 * ver [buildFaqItems]), sem nenhuma fonte de dado dinâmica.
 *
 * Lista expansível: um item por vez fica aberto (tocar em outro fecha o
 * anterior automaticamente) - decisão de design deliberada para manter a
 * tela curta e fácil de escanear, já que são só 6 perguntas; se no futuro
 * a lista crescer muito, vale reconsiderar permitir múltiplos abertos
 * simultaneamente.
 */
class FaqActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaqBinding
    private lateinit var adapter: FaqAdapter
    private var items = mutableListOf<FaqItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        items = buildFaqItems().toMutableList()
        adapter = FaqAdapter { position -> toggleItem(position) }
        binding.recyclerFaq.layoutManager = LinearLayoutManager(this)
        binding.recyclerFaq.adapter = adapter
        adapter.submitList(items)
    }

    /** Alterna o item tocado; recolhe qualquer outro que estivesse aberto (ver KDoc da classe). */
    private fun toggleItem(position: Int) {
        val wasExpanded = items[position].isExpanded
        items = items.mapIndexed { index, item ->
            item.copy(isExpanded = if (index == position) !wasExpanded else false)
        }.toMutableList()
        adapter.submitList(items)
    }

    /**
     * Conteúdo definitivo da FAQ (v0.9.3) - texto vindo do `strings.xml`
     * (traduzível para en/es/fr no item final desta versão), não
     * hardcoded aqui.
     */
    private fun buildFaqItems(): List<FaqItem> = listOf(
        FaqItem(getString(R.string.faq_q1), getString(R.string.faq_a1)),
        FaqItem(getString(R.string.faq_q2), getString(R.string.faq_a2)),
        FaqItem(getString(R.string.faq_q3), getString(R.string.faq_a3)),
        FaqItem(getString(R.string.faq_q4), getString(R.string.faq_a4)),
        FaqItem(getString(R.string.faq_q5), getString(R.string.faq_a5)),
        FaqItem(getString(R.string.faq_q6), getString(R.string.faq_a6))
    )
}
