package com.example.smartremote.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.diagnostic.DiagnosticLogType
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.TvDevice

/**
 * *** NOVO - v0.9, item 1 (reconexão automática) ***
 *
 * Orquestra as tentativas de reconexão automática com backoff, e reage a
 * mudanças de rede (Wi-Fi caiu e voltou) via [ConnectivityManager]. Não
 * sabe nada sobre fabricante/protocolo - só chama [TvManager.connect] de
 * novo com `isAutomaticReconnect = true`, reaproveitando inteiramente o
 * fluxo de conexão já existente (mesmo TvController, mesmo
 * TvConnectionListener) em vez de duplicar qualquer lógica de protocolo.
 * Isso mantém a regra do projeto de que só o TvController de cada
 * fabricante sabe decidir o que fazer durante uma tentativa de conexão -
 * este objeto só decide QUANDO tentar de novo.
 *
 * Continua sendo um `object` (singleton), no mesmo espírito de
 * [TvManager]/[ConnectionManager]: só existe uma "campanha" de
 * reconexão de cada vez, para a única conexão ativa que o app suporta.
 *
 * ## Estratégia de backoff
 * Fixa e crescente (não é exponencial "puro" para não demorar demais nas
 * primeiras tentativas, mas também não pode ficar batendo a cada
 * segundo - risco citado explicitamente no pedido da v0.9): 2s, 5s, 10s,
 * 20s, 40s, e a partir daí sempre 60s, indefinidamente (uma queda de
 * Wi-Fi pode durar bastante, e o app não tem como saber se/quando vai
 * voltar - melhor continuar tentando devagar do que desistir de vez).
 * Qualquer chamada bem-sucedida ([onReconnected]) ou definitivamente sem
 * saída ([cancel] via `onConnectionLost(recoverable = false)`) encerra a
 * campanha atual.
 *
 * ## Limitação conhecida (documentada para o usuário validar)
 * O agendamento usa `Handler(Looper.getMainLooper()).postDelayed`, ou
 * seja, só funciona enquanto o PROCESSO do app estiver vivo (app aberto
 * ou recém-minimizado). Não existe (nem foi pedido nesta versão) um
 * Foreground Service para manter isso rodando com o app completamente
 * fechado/o processo morto pelo sistema - se isso acontecer, a
 * reconexão automática só volta a acontecer quando o usuário reabrir o
 * app (via [TvManager.reconnectIfNeeded], chamado no onStart da
 * MainActivity), não enquanto ele está fechado. Trade-off deliberado
 * para não introduzir a complexidade de um Foreground Service (e a
 * notificação persistente que ele exigiria) em uma versão cujo objetivo
 * era focar em corrigir a arquitetura existente.
 */
object ReconnectionManager {

    private val BACKOFF_SCHEDULE_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L)

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    private var attempt = 0

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var monitoredContext: Context? = null

    // ===================== BACKOFF =====================

    /**
     * Agenda uma nova tentativa de reconexão com [device], respeitando o
     * backoff. Chamado pelo [TvManager] sempre que uma conexão que já
     * estava ativa cai inesperadamente ([TvConnectionListener.onConnectionLost]
     * com `recoverable = true`), ou quando uma tentativa automática em
     * andamento falha por um motivo que não é definitivo (rede
     * momentaneamente fora do ar, TV não respondeu a tempo, etc.).
     *
     * Substitui qualquer tentativa já agendada (não empilha múltiplos
     * retries concorrentes para o mesmo dispositivo).
     */
    fun scheduleReconnect(context: Context, device: TvDevice) {
        cancelPending()
        attempt++
        val delayMs = BACKOFF_SCHEDULE_MS.getOrElse(attempt - 1) { BACKOFF_SCHEDULE_MS.last() }

        DiagnosticManager.log(
            "Reconexão automática: tentativa #$attempt agendada em ${delayMs}ms",
            DiagnosticLogType.INFO
        )

        val runnable = Runnable { attemptReconnect(context, device) }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Reconexão imediata (backoff resetado), usada quando um sinal mais
     * forte que "vale a pena tentar de novo" acontece - hoje só a rede
     * voltar depois de estar indisponível (ver [onNetworkAvailable]).
     * Não faz sentido esperar o resto de um backoff antigo (calculado
     * quando não se sabia que a rede tinha voltado) nesse caso.
     */
    private fun triggerImmediateRetry(context: Context, device: TvDevice) {
        cancelPending()
        attempt = 0
        DiagnosticManager.log("Rede disponível de novo - tentando reconectar imediatamente", DiagnosticLogType.INFO)
        attemptReconnect(context, device)
    }

    private fun attemptReconnect(context: Context, device: TvDevice) {
        pendingRunnable = null
        // Listener silencioso: TvManager.connect() já loga tudo que
        // importa no DiagnosticManager, e já decide sozinho (via
        // onConnected/onError/onConnectionLost do wrapper interno) se
        // deve agendar a PRÓXIMA tentativa ou desistir. A UI (se estiver
        // com a tela de descoberta/controle aberta) continua sendo
        // avisada de qualquer jeito, porque é o MESMO TvController que
        // permanece ativo em TvManager - este listener aqui é só o que
        // fecha o ciclo desta chamada de connect() específica.
        TvManager.connect(
            context,
            device,
            object : TvConnectionListener {
                override fun onConnected() {}
                override fun onPairingRequired() {}
                override fun onError(message: String) {}
            },
            isAutomaticReconnect = true
        )
    }

    /** Chamado pelo TvManager quando uma conexão (automática ou não) é confirmada. */
    fun onReconnected() {
        cancelPending()
        attempt = 0
    }

    /**
     * Cancela qualquer tentativa agendada e zera a contagem de backoff -
     * sem tentar reconectar. Usado tanto por desconexões explícitas do
     * usuário quanto por quedas marcadas como "não recuperáveis"
     * (credencial rejeitada, sem credencial salva para reconexão
     * automática).
     */
    fun cancel() {
        cancelPending()
        attempt = 0
    }

    private fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }

    // ===================== MUDANÇAS DE REDE =====================

    /**
     * Começa a observar mudanças de conectividade via
     * [ConnectivityManager.NetworkCallback] - chamado pela MainActivity
     * em `onStart()`. Complementa o backoff por tempo: se o Wi-Fi cair e
     * voltar, não faz sentido esperar o resto de um backoff de 40s só
     * porque foi aí que a última tentativa aconteceu de coincidência -
     * uma rede disponível de novo é um sinal direto de "vale tentar
     * agora".
     *
     * Seguro chamar mais de uma vez (reregistra sem duplicar callback).
     */
    fun startNetworkMonitor(context: Context) {
        stopNetworkMonitor()

        val appContext = context.applicationContext
        monitoredContext = appContext
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable(appContext)
            }
        }
        networkCallback = callback

        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            // Alguns fabricantes restringem NetworkRequest sem
            // ACCESS_NETWORK_STATE em versões antigas - a permissão já
            // existe no manifest do projeto, mas por segurança não
            // derruba o app se algo assim acontecer; a reconexão por
            // backoff continua funcionando normalmente sem o gatilho
            // extra de rede.
            networkCallback = null
            DiagnosticManager.log("Não foi possível monitorar mudanças de rede: ${e.message}", DiagnosticLogType.WARNING)
        }
    }

    /** Para de observar mudanças de conectividade - chamado pela MainActivity em `onStop()`. */
    fun stopNetworkMonitor() {
        val context = monitoredContext ?: return
        val callback = networkCallback ?: return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Callback já tinha sido removido (ex: chamado duas vezes) - inofensivo.
        }
        networkCallback = null
        monitoredContext = null
    }

    private fun onNetworkAvailable(context: Context) {
        // Só interessa se existir uma TV pareada que NÃO está conectada
        // agora - se já está tudo certo, ou se nunca houve pareamento,
        // não há o que fazer aqui.
        if (TvManager.isConnected()) return
        val device = TvManager.getLastConnectedDevice(context) ?: return
        triggerImmediateRetry(context, device)
    }
}
