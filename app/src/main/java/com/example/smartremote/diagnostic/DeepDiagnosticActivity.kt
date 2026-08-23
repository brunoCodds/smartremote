package com.example.smartremote.diagnostic

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartremote.databinding.ActivityDeepDiagnosticBinding
import com.example.smartremote.discovery.DiscoveryDiagnostics
import com.example.smartremote.discovery.DiscoveryEventAdapter

/**
 * *** NOVO - v0.9.3, item 4 ***
 *
 * "Diagnóstico Aprofundado" - contraparte técnica/crua do painel simples
 * (item 2, ícone "i" da tela principal). MESMA fonte de dado
 * ([DiagnosticManager] para conexão, [DiscoveryDiagnostics] para
 * descoberta) - nunca duplica estado, só apresenta de forma mais completa
 * e sem tratamento:
 * - Bloco "Dispositivo"/"Conexão": dump literal `campo: valor` de TODOS os
 *   campos do [DiagnosticState] (inclusive os que o painel simples omite
 *   ou já resume, como [DiagnosticState.isAutoReconnecting]) - ao
 *   contrário do painel simples, que usa rótulos amigáveis
 *   ("Último comando") e omite campos internos.
 * - Log de eventos de conexão: mesmas [DiagnosticLogEntry] do painel
 *   simples (reaproveita [DiagnosticLogAdapter], já colorido por tipo -
 *   ver item 2), mas SEM o limite de espaço/sobreposição ao controle
 *   remoto que o painel simples tem - rolagem livre, tela cheia.
 * - Log de eventos de descoberta: novo para esta versão -
 *   [DiscoveryDiagnostics.snapshot] não tinha NENHUMA tela própria antes;
 *   só existia como fonte de `Log.d` do Logcat. Responde "por que outras
 *   TVs não foram encontradas na última busca" sem precisar abrir o
 *   Logcat.
 */
class DeepDiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeepDiagnosticBinding
    private val logAdapter = DiagnosticLogAdapter()
    private val discoveryAdapter = DiscoveryEventAdapter()

    private val diagnosticListener = DiagnosticManager.Listener { state, logs ->
        renderDevice(state)
        renderEvents(logs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeepDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerDeepEvents.layoutManager = LinearLayoutManager(this)
        binding.recyclerDeepEvents.adapter = logAdapter
        binding.recyclerDeepDiscovery.layoutManager = LinearLayoutManager(this)
        binding.recyclerDeepDiscovery.adapter = discoveryAdapter

        // Snapshot único da descoberta ao abrir a tela - DiscoveryDiagnostics
        // não tem um mecanismo de listener/observer como o DiagnosticManager
        // (é só um buffer circular gravado pelos scanners durante uma
        // busca), então não há "atualização ao vivo" aqui: se o usuário
        // quiser ver uma busca mais recente, reabre esta tela depois de
        // buscar de novo na tela de pareamento.
        renderDiscovery()
    }

    override fun onStart() {
        super.onStart()
        // addListener já entrega o snapshot atual (state + logs) de
        // imediato para quem acabou de se inscrever (ver
        // DiagnosticManager.addListener/notifyListener) - não é preciso
        // (nem possível: state/logs são privados) buscar um valor inicial
        // à parte aqui, mesmo padrão já usado em
        // MainActivity.setupDiagnosticPanel().
        DiagnosticManager.addListener(diagnosticListener)
    }

    override fun onStop() {
        DiagnosticManager.removeListener(diagnosticListener)
        super.onStop()
    }

    /** Dump literal de todos os campos "de identidade" do dispositivo - sem rótulo amigável, sem alinhamento decorativo. */
    private fun renderDevice(state: DiagnosticState) {
        binding.txtDeepDevice.text = listOf(
            "ip: ${state.ip ?: "null"}",
            "brand: ${state.brand ?: "null"}",
            "model: ${state.model ?: "null"}",
            "name: ${state.name ?: "null"}",
            "os: ${state.os ?: "null"}",
            "controllerName: ${state.controllerName ?: "null"}",
            "protocol: ${state.protocol ?: "null"}"
        ).joinToString(separator = "\n")

        binding.txtDeepConnection.text = listOf(
            "connectionStatus: ${state.connectionStatus}",
            "isAutoReconnecting: ${state.isAutoReconnecting}",
            "pingMs: ${state.pingMs?.toString() ?: "null"}",
            "tokenMasked: ${state.tokenMasked ?: "null"}",
            "lastCommand: ${state.lastCommand ?: "null"}",
            "lastResponse: ${state.lastResponse ?: "null"}",
            "lastError: ${state.lastError ?: "null"}"
        ).joinToString(separator = "\n")
    }

    private fun renderEvents(logs: List<DiagnosticLogEntry>) {
        binding.txtDeepEventsEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        logAdapter.submitList(logs)
    }

    private fun renderDiscovery() {
        val events = DiscoveryDiagnostics.snapshot()
        binding.txtDeepDiscoveryEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        discoveryAdapter.submitList(events)
    }
}
