package com.example.smartremote.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.smartremote.model.RemoteKey

/**
 * Descreve um card de app dentro do AppsBottomSheet: puramente visual +
 * o RemoteKey que ele dispara. Não sabe nada de Samsung, App ID ou
 * protocolo - isso fica isolado em cada TvController (ver
 * TvController.supportedApps() para saber se o app é suportado pela TV
 * conectada).
 *
 * *** NOVO - v0.9.6, item 7 ***: [legacyIconBackgroundRes] e
 * [descriptionRes] foram adicionados pra este mesmo catálogo (agora em
 * [AppCatalog]) também alimentar os 3 atalhos configuráveis da tela
 * principal (rowStreaming) e o picker de "apps preferidos" em
 * Configurações - sem duplicar a lista de apps em três lugares.
 * `legacyIconBackgroundRes` é o drawable `legacy_ripple_circle_*` usado
 * pelos botões pequenos de 56dp da tela principal (visualmente diferente
 * do ícone grande da grade do AppsBottomSheet, que usa [iconBackgroundRes]).
 */
data class AppItem(
    val key: RemoteKey,
    val label: String,
    val iconLetter: String,
    @DrawableRes val iconBackgroundRes: Int,
    @DrawableRes val legacyIconBackgroundRes: Int,
    @StringRes val descriptionRes: Int
)
