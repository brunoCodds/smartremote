package com.example.smartremote.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * *** v0.9.3, item 3 ("Mudar idioma") ***
 *
 * Troca do idioma do app via `AppCompatDelegate.setApplicationLocales`
 * (AppCompat 1.6+, disponível aqui - projeto usa 1.7.1) - o AppCompat cuida
 * de recriar as Activities visíveis automaticamente e, a partir do Android
 * 13, delega para a API nativa `LocaleManager` do sistema.
 *
 * *** CORREÇÃO - a escolha não sobrevivia a reabrir o app ***: a
 * documentação do AppCompat promete persistência automática entre sessões
 * (via um `ContentProvider` registrado pela própria lib,
 * `AppLocalesMetadataHolderService`) SEM precisar guardar nada à parte -
 * essa era a suposição original deste arquivo (ver comentário removido
 * abaixo). Na prática, isso não estava se confirmando: o idioma voltava
 * para o padrão (português) toda vez que o processo do app era encerrado e
 * reaberto. Em vez de investigar a fundo POR QUE o mecanismo automático
 * não estava pegando neste projeto (poderia ser qualquer coisa: timing de
 * `attachBaseContext`, uma peculiaridade de versão do AppCompat, um
 * conflito com outra configuração do manifest), a correção escolhida foi
 * parar de depender dele: persistir a escolha explicitamente em
 * `SharedPreferences` e reaplicá-la manualmente no processo mais cedo
 * possível - [SmartRemoteApplication.onCreate], que roda ANTES de
 * qualquer Activity, garantindo que `AppCompatDelegate` já esteja com o
 * idioma certo antes da primeira tela ser inflada (evita o "flash" de UI
 * em português por uma fração de segundo antes de recriar em outro
 * idioma). Essa abordagem funciona de forma garantida independentemente
 * de qualquer comportamento interno do AppCompat.
 *
 * As 4 opções são fixas (Português, English, Español, Français) - sem
 * opção "padrão do sistema", conforme decisão explícita do escopo desta
 * versão (o padrão do app já É o português quando nenhuma escolha foi
 * feita ainda).
 */
object LanguageManager {

    enum class AppLanguage(val tag: String) {
        PORTUGUESE("pt"),
        ENGLISH("en"),
        SPANISH("es"),
        FRENCH("fr")
    }

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    /**
     * Aplica o idioma escolhido - recria as Activities visíveis
     * automaticamente (comportamento do AppCompatDelegate) - e PERSISTE a
     * escolha em SharedPreferences, para [applyPersistedLanguage] poder
     * reaplicá-la no próximo início do processo.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE_TAG, language.tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /**
     * Reaplica o idioma salvo (se houver) no `AppCompatDelegate`. Chamado
     * exclusivamente por [SmartRemoteApplication.onCreate] - é o que
     * efetivamente resolve o bug de "esquece o idioma ao reabrir o app".
     * Se nunca houve uma escolha salva, não faz nada (mantém o padrão do
     * app, português, sem forçar `setApplicationLocales` à toa a cada
     * abertura).
     */
    fun applyPersistedLanguage(context: Context) {
        val savedTag = prefs(context).getString(KEY_LANGUAGE_TAG, null) ?: return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedTag))
    }

    /**
     * Idioma atualmente ativo, para marcar a opção correspondente
     * selecionada no diálogo. Cai para PORTUGUESE (idioma padrão do app)
     * se nenhuma escolha explícita foi feita ainda (lista vazia) ou se o
     * valor salvo não corresponder a nenhuma das 4 opções suportadas.
     */
    fun getCurrentLanguage(): AppLanguage {
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return AppLanguage.values().firstOrNull { currentTag.startsWith(it.tag) } ?: AppLanguage.PORTUGUESE
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

