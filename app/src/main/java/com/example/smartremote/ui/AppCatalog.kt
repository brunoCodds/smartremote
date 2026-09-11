package com.example.smartremote.ui

import com.example.smartremote.R
import com.example.smartremote.model.RemoteKey

/**
 * *** NOVO - v0.9.6, item 7 ***
 *
 * Catálogo único dos 10 apps de streaming que o app já conhece (mesma
 * lista que antes vivia só dentro de [AppsBottomSheet]) - agora
 * compartilhado também pelos 3 atalhos configuráveis da tela principal
 * (rowStreaming) e pelo picker de "apps preferidos" em
 * `SettingsActivity`. Continua puramente visual: quem decide se um app
 * específico funciona na TV conectada é `TvManager.getSupportedApps()`
 * (ver KDoc original em [AppsBottomSheet]).
 *
 * Adicionar um app novo no futuro = uma linha aqui (mais os drawables
 * `ripple_circle_*`/`legacy_ripple_circle_*` e a string `desc_*`
 * correspondentes, se ainda não existirem) - nenhum dos três lugares que
 * consomem este catálogo precisa mudar.
 */
object AppCatalog {

    val apps: List<AppItem> = listOf(
        AppItem(RemoteKey.NETFLIX, "Netflix", "N", R.drawable.ripple_circle_netflix, R.drawable.legacy_ripple_circle_netflix, R.string.desc_netflix),
        AppItem(RemoteKey.PRIME_VIDEO, "Prime Video", "P", R.drawable.ripple_circle_prime, R.drawable.legacy_ripple_circle_prime, R.string.desc_prime),
        AppItem(RemoteKey.YOUTUBE, "YouTube", "Y", R.drawable.ripple_circle_youtube, R.drawable.legacy_ripple_circle_youtube, R.string.desc_youtube),
        AppItem(RemoteKey.DISNEY_PLUS, "Disney+", "D", R.drawable.ripple_circle_disney, R.drawable.legacy_ripple_circle_disney, R.string.desc_disney_plus),
        AppItem(RemoteKey.MAX, "Max", "M", R.drawable.ripple_circle_max, R.drawable.legacy_ripple_circle_max, R.string.desc_max),
        AppItem(RemoteKey.GLOBOPLAY, "Globoplay", "G", R.drawable.ripple_circle_globoplay, R.drawable.legacy_ripple_circle_globoplay, R.string.desc_globoplay),
        AppItem(RemoteKey.APPLE_TV_PLUS, "Apple TV+", "tv", R.drawable.ripple_circle_appletv, R.drawable.legacy_ripple_circle_appletv, R.string.desc_apple_tv_plus),
        AppItem(RemoteKey.PARAMOUNT_PLUS, "Paramount+", "P+", R.drawable.ripple_circle_paramount, R.drawable.legacy_ripple_circle_paramount, R.string.desc_paramount_plus),
        AppItem(RemoteKey.CRUNCHYROLL, "Crunchyroll", "C", R.drawable.ripple_circle_crunchyroll, R.drawable.legacy_ripple_circle_crunchyroll, R.string.desc_crunchyroll),
        AppItem(RemoteKey.PLEX, "Plex", "Px", R.drawable.ripple_circle_plex, R.drawable.legacy_ripple_circle_plex, R.string.desc_plex)
    )

    fun byKey(key: RemoteKey): AppItem? = apps.firstOrNull { it.key == key }
}
