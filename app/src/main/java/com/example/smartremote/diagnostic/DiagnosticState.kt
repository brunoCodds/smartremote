package com.example.smartremote.diagnostic

/**
 * Snapshot imutável do estado atual de diagnóstico, mantido pelo
 * [DiagnosticManager]. A MainActivity apenas lê esses campos para
 * renderizar o painel - nenhum campo é calculado por ela.
 */
data class DiagnosticState(
    val ip: String? = null,
    val brand: String? = null,
    val model: String? = null,
    /**
     * *** NOVO - v0.9.3, item 1 ***: nome de exibição do TvDevice (ex:
     * "Sala - Samsung"), usado como último fallback para o indicador de
     * "Reconectando a {nome}" quando marca/modelo não estiverem
     * disponíveis (ver [com.example.smartremote.MainActivity.buildReconnectDeviceLabel]).
     * Não é usado em nenhum outro lugar do painel de diagnóstico hoje.
     */
    val name: String? = null,
    val os: String? = null,
    val controllerName: String? = null,
    val protocol: String? = null,
    val connectionStatus: String = "Desconectado",
    val pingMs: Long? = null,
    val tokenMasked: String? = null,
    val lastCommand: String? = null,
    val lastResponse: String? = null,
    val lastError: String? = null,
    /**
     * *** NOVO - v0.9.3, item 1 ***: `true` enquanto existe uma "campanha"
     * de reconexão AUTOMÁTICA em andamento (aguardando o backoff do
     * [com.example.smartremote.manager.ReconnectionManager] ou tentando de
     * fato via [com.example.smartremote.manager.TvManager.reconnectIfNeeded]).
     *
     * Deliberadamente SEPARADO de [connectionStatus] - o texto
     * "Reconectando" já é usado por [connectionStatus] em um caso
     * completamente diferente (SamsungTizenController reabre o socket
     * com o token logo após o usuário aceitar o pareamento manual), então
     * comparar `connectionStatus == "Reconectando"` acenderia o indicador
     * também durante um pareamento manual novo, o que o item 1 explicitamente
     * não quer. Este flag é a única fonte de verdade para o indicador.
     */
    val isAutoReconnecting: Boolean = false
)
