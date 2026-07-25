package com.example.smartremote.ui

import androidx.annotation.DrawableRes
import com.example.smartremote.model.RemoteKey

/**
 * Descreve um card de app dentro do AppsBottomSheet: puramente visual +
 * o RemoteKey que ele dispara. Não sabe nada de Samsung, App ID ou
 * protocolo - isso fica isolado em cada TvController (ver
 * TvController.supportedApps() para saber se o app é suportado pela TV
 * conectada).
 */
data class AppItem(
    val key: RemoteKey,
    val label: String,
    val iconLetter: String,
    @DrawableRes val iconBackgroundRes: Int
)
