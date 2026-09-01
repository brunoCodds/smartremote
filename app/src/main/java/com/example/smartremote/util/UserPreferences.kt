package com.example.smartremote.util

import android.content.Context

/**
 * *** NOVO - v0.9.5 (configurações de auxílio ao usuário) ***
 *
 * Dois toggles simples, direto no rodapé do menu lateral (não uma tela de
 * Configurações completa - essa fica pra mais pra frente, quando tiver
 * itens de verdade tipo tamanho/posição dos botões e permissões). Mesmo
 * padrão de persistência já usado em [LanguageManager]: SharedPreferences
 * dedicado, lido/escrito sempre com o valor efetivo (sem cache em
 * memória), já que aqui não há nenhum custo de performance que
 * justifique isso.
 */
object UserPreferences {

    private const val PREFS_NAME = "user_assist_prefs"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"

    /** Padrão `true` - esperado de um app de controle remoto usado ativamente não deixar a tela apagar sozinha no meio do uso. */
    fun isKeepScreenOnEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    /** Padrão `true` - mesmo comportamento que o app já tinha antes deste toggle existir (feedback de vibração sempre ligado). */
    fun isVibrationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATION_ENABLED, true)

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
