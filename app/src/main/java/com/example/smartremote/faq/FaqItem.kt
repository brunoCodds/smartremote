package com.example.smartremote.faq

/**
 * Um par pergunta/resposta da tela de FAQ, com o estado de
 * expandido/recolhido embutido - mais simples que manter um Set<Int> de
 * posições expandidas à parte, e como a lista é pequena e fixa (6 itens,
 * ver [FaqActivity.buildFaqItems]), o custo de copiar a lista inteira a
 * cada toque é irrelevante.
 */
data class FaqItem(
    val question: String,
    val answer: String,
    val isExpanded: Boolean = false
)
