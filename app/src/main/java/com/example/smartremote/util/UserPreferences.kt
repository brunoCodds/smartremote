package com.example.smartremote.util

import android.content.Context
import com.example.smartremote.model.RemoteKey

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
    private const val KEY_CURSOR_SENSITIVITY = "cursor_sensitivity"
    private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
    private const val KEY_POWER_OFF_CONFIRMATION_ENABLED = "power_off_confirmation_enabled"
    private const val KEY_SHOW_ASSISTANT_BUTTON = "show_assistant_button"
    private const val KEY_SHOW_ABC_BUTTON = "show_abc_button"
    private const val KEY_PREFERRED_APP_SLOT_PREFIX = "preferred_app_slot_"

    /** *** NOVO - v0.9.6, item 2 ***: mesmos limites usados pelo slider em SettingsActivity - intervalo escolhido em torno do palpite inicial (1.5f), sem validação real contra uso variado (mesma ressalva que já existia na constante fixa). */
    const val CURSOR_SENSITIVITY_MIN = 0.5f
    const val CURSOR_SENSITIVITY_MAX = 3.0f
    const val CURSOR_SENSITIVITY_DEFAULT = 1.5f

    /** *** NOVO - v0.9.6, item 7 ***: os 3 slots configuráveis (rowStreaming da tela principal) nascem com os mesmos apps que já eram fixos ali antes desta versão - ninguém que nunca abrir Configurações percebe diferença. */
    private val PREFERRED_APP_SLOT_DEFAULTS = listOf(RemoteKey.NETFLIX, RemoteKey.PRIME_VIDEO, RemoteKey.GLOBOPLAY)

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

    /**
     * *** NOVO - v0.9.6, item 2 ***: substitui a constante fixa
     * `CURSOR_SENSITIVITY` que existia em `MainActivity` (era um palpite
     * inicial hardcoded, documentado como tal desde a v0.9.4). Padrão
     * mantém o mesmo valor de antes ([CURSOR_SENSITIVITY_DEFAULT] = 1.5f)
     * para não mudar o comportamento de ninguém que já estava usando o
     * modo cursor sem nunca ter aberto a tela de Configurações.
     */
    fun getCursorSensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_CURSOR_SENSITIVITY, CURSOR_SENSITIVITY_DEFAULT)

    /** Sempre grava um valor dentro de [CURSOR_SENSITIVITY_MIN]..[CURSOR_SENSITIVITY_MAX], mesmo que quem chame passe algo fora do intervalo. */
    fun setCursorSensitivity(context: Context, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(CURSOR_SENSITIVITY_MIN, CURSOR_SENSITIVITY_MAX)
        prefs(context).edit().putFloat(KEY_CURSOR_SENSITIVITY, clamped).apply()
    }

    /**
     * *** NOVO - v0.9.6, item 3 ***: padrão `true` - mesmo comportamento
     * que o app sempre teve (reconexão automática sempre ativa, sem
     * nenhum jeito de desligar até esta versão). Lida por
     * [ReconnectionManager] no início de [ReconnectionManager.scheduleReconnect]
     * e do gatilho de rede voltando ([ReconnectionManager.triggerImmediateRetry]
     * via `onNetworkAvailable`) - os dois caminhos de reconexão automática
     * que existem hoje.
     */
    fun isAutoReconnectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECONNECT_ENABLED, true)

    fun setAutoReconnectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECONNECT_ENABLED, enabled).apply()
    }

    /**
     * *** NOVO - v0.9.6, item 4 ***: padrão `false` (decisão já tomada no
     * documento de planejamento - não pergunta antes de desligar a TV a
     * não ser que a pessoa ligue esse toggle explicitamente).
     */
    fun isPowerOffConfirmationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POWER_OFF_CONFIRMATION_ENABLED, false)

    fun setPowerOffConfirmationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POWER_OFF_CONFIRMATION_ENABLED, enabled).apply()
    }

    // ===================== v0.9.6, item 7 (quais botões aparecem) =====================

    /** Padrão `true` - os dois botões continuam visíveis pra quem nunca abrir Configurações. */
    fun isAssistantButtonVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ASSISTANT_BUTTON, true)

    fun setAssistantButtonVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ASSISTANT_BUTTON, visible).apply()
    }

    fun isAbcButtonVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ABC_BUTTON, true)

    fun setAbcButtonVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ABC_BUTTON, visible).apply()
    }

    // ===================== v0.9.6, item 7 (apps preferidos) =====================

    /**
     * `slot` é 1, 2 ou 3 - os três atalhos fixos de `rowStreaming` na tela
     * principal (posição/quantidade não mudam aqui, só QUAL app cada um
     * dispara - isso é trabalho do item 9, arrastar/grade). Grava o nome
     * do enum ([RemoteKey.name]) como String; se o valor salvo não bater
     * com nenhum [RemoteKey] válido (ex: instalação antiga, dado
     * corrompido), cai no padrão daquele slot em vez de derrubar o app.
     */
    fun getPreferredAppSlot(context: Context, slot: Int): RemoteKey {
        val default = PREFERRED_APP_SLOT_DEFAULTS.getOrElse(slot - 1) { RemoteKey.NETFLIX }
        val stored = prefs(context).getString(KEY_PREFERRED_APP_SLOT_PREFIX + slot, null) ?: return default
        return try {
            RemoteKey.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    fun setPreferredAppSlot(context: Context, slot: Int, key: RemoteKey) {
        prefs(context).edit().putString(KEY_PREFERRED_APP_SLOT_PREFIX + slot, key.name).apply()
    }

    /**
     * *** NOVO - v0.9.6, item 10 ***: restaura os itens de Configurações
     * que existem até agora (sensibilidade do cursor, reconexão automática,
     * confirmação de power-off, quais botões aparecem, apps preferidos).
     * Não mexe nos dois toggles de auxílio do drawer (manter tela ligada /
     * vibração) - são de outra tela, com vida própria, e o documento de
     * planejamento não pede pra incluí-los aqui. Itens futuros (tamanho/
     * posição dos botões) entram nesta função quando forem implementados.
     */
    fun resetToDefaults(context: Context) {
        prefs(context).edit()
            .remove(KEY_CURSOR_SENSITIVITY)
            .remove(KEY_AUTO_RECONNECT_ENABLED)
            .remove(KEY_POWER_OFF_CONFIRMATION_ENABLED)
            .remove(KEY_SHOW_ASSISTANT_BUTTON)
            .remove(KEY_SHOW_ABC_BUTTON)
            .remove(KEY_PREFERRED_APP_SLOT_PREFIX + "1")
            .remove(KEY_PREFERRED_APP_SLOT_PREFIX + "2")
            .remove(KEY_PREFERRED_APP_SLOT_PREFIX + "3")
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
