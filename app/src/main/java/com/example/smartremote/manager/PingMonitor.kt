package com.example.smartremote.manager

import android.os.Handler
import android.os.Looper
import com.example.smartremote.diagnostic.DiagnosticManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * *** NOVO - v0.9.3 (correção: "Ping" do painel de diagnóstico nunca era
 * preenchido) ***
 *
 * `DiagnosticManager.setPing()` já existia desde antes desta versão, mas
 * nenhum código do projeto o chamava - o campo "Ping" do painel sempre
 * mostrava "—". Não existe uma forma simples de fazer ICMP ping real a
 * partir do Android sem root (`InetAddress.isReachable()` tenta ICMP mas
 * cai silenciosamente para TCP echo na porta 7 em quase todo aparelho, com
 * resultado pouco confiável) - a medida usada aqui é o tempo de
 * ESTABELECER uma conexão TCP até a porta de controle da TV
 * ([Socket.connect] com timeout curto), que é uma proxy honesta e
 * consistente de latência de rede até a TV (mesmo princípio usado por
 * ferramentas como `tcping`), sem exigir nada mais do que a TV já expor
 * (a porta de controle, que por definição está aberta enquanto conectados
 * a ela).
 *
 * Roda em loop enquanto há uma conexão ativa - inicia em
 * [TvManager.connect] (dentro do `onConnected` do wrapper) e para em
 * qualquer caminho que encerre a conexão ([TvManager.disconnect],
 * `onConnectionLost`, `onError`) - ver os pontos de chamada em TvManager.
 */
object PingMonitor {

    private const val INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 2_000

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var isRunning = false
    private var targetIp: String? = null
    private var targetPort: Int = 0

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            measureOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Inicia o loop de medição para (ip, port). Reinicia se já estava rodando para outro alvo. */
    fun start(ip: String, port: Int) {
        if (isRunning && targetIp == ip && targetPort == port) return
        stop()
        targetIp = ip
        targetPort = port
        isRunning = true
        // Primeira medição imediata (não espera os 10s iniciais) - o
        // usuário abrindo o painel logo após conectar já vê um valor.
        handler.post(loopRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(loopRunnable)
        DiagnosticManager.setPing(null)
    }

    private fun measureOnce() {
        val ip = targetIp ?: return
        val port = targetPort
        executor.execute {
            val elapsedMs = try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                }
                System.currentTimeMillis() - start
            } catch (e: Exception) {
                null
            }
            // Só publica se ainda estivermos medindo o mesmo alvo - evita
            // uma medição tardia (thread ainda rodando) sobrescrever o
            // ping com um resultado de uma TV da qual já desconectamos.
            handler.post {
                if (isRunning && targetIp == ip && targetPort == port) {
                    DiagnosticManager.setPing(elapsedMs)
                }
            }
        }
    }
}
