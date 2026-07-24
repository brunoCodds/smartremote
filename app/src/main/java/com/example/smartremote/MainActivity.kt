package com.example.smartremote

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.databinding.ActivityMainBinding
import com.example.smartremote.diagnostic.DiagnosticLogEntry
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.diagnostic.DiagnosticState
import com.example.smartremote.discovery.DeviceDiscoveryActivity
import com.example.smartremote.manager.TvManager

/**
 * Tela única do Smart Remote (v1).
 *
 * Nesta versão o app apenas exibe a interface do controle e reage aos toques
 * com Toast + Log + animação de clique + haptic feedback. A comunicação real
 * com a Smart TV será adicionada em uma versão futura (ex: substituindo o
 * corpo de cada função de ação por uma chamada a um "tvController").
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val TAG = "SmartRemote"
        private const val CLICK_ANIM_DURATION_MS = 50L // down + up = ~100ms
        private const val CLICK_ANIM_SCALE = 0.95f

        private const val DIAGNOSTIC_ANIM_DURATION_MS = 200L
        private const val DIAGNOSTIC_SLIDE_DISTANCE_DP = 40f
        private const val DIAGNOSTIC_LABEL_COLUMN_WIDTH = 18
    }

    /** Recebe as atualizações do DiagnosticManager e apenas repassa para renderDiagnostic(). */
    private val diagnosticListener = DiagnosticManager.Listener { state, logs ->
        renderDiagnostic(state, logs)
    }

    private var isDiagnosticPanelOpen = false

    private val diagnosticSlideDistancePx: Float by lazy {
        DIAGNOSTIC_SLIDE_DISTANCE_DP * resources.displayMetrics.density
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableFullscreenMode()
        setupClickListeners()
        setupDiagnosticPanel()
    }

    override fun onDestroy() {
        DiagnosticManager.removeListener(diagnosticListener)
        super.onDestroy()
    }

    /** Deixa a tela em modo imersivo (sem barra de status/navegação). */
    private fun enableFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Centraliza a configuração de todos os cliques do controle remoto. */
    private fun setupClickListeners() {
        with(binding) {
            // Diagnóstico (painel de debug, apenas dev)
            btnInfo.setOnClickListener { toggleDiagnosticPanel() }

            // Configurações (procurar/conectar TV)
            btnSettings.setOnClickListener { openDeviceDiscovery() }

            // Topo
            btnPower.setOnClickListener { power() }
            // Pressionar e segurar o Power abre a configuração/pareamento de TV.
            // Escolhido para não exigir nenhum novo elemento visual na interface atual.
            btnPower.setOnLongClickListener { openDeviceDiscovery(); true }
            btnKeyboard.setOnClickListener { keyboard() }
            btnAssistant.setOnClickListener { assistant() }

            // D-pad
            btnDpadUp.setOnClickListener { dpadUp() }
            btnDpadDown.setOnClickListener { dpadDown() }
            btnDpadLeft.setOnClickListener { dpadLeft() }
            btnDpadRight.setOnClickListener { dpadRight() }
            btnDpadOk.setOnClickListener { dpadOk() }

            // Controles do meio
            btnBack.setOnClickListener { back() }
            btnHome.setOnClickListener { home() }
            btnPlayPause.setOnClickListener { playPause() }

            // Volume e canal
            btnVolumeUp.setOnClickListener { volumeUp() }
            btnVolumeDown.setOnClickListener { volumeDown() }
            btnChannelUp.setOnClickListener { channelUp() }
            btnChannelDown.setOnClickListener { channelDown() }

            // Streaming
            btnNetflix.setOnClickListener { netflix() }
            btnPrimeVideo.setOnClickListener { primeVideo() }
            btnGloboplay.setOnClickListener { globoplay() }
        }
    }

    /** Abre a tela de descoberta/pareamento de Smart TVs na rede local. */
    private fun openDeviceDiscovery() {
        startActivity(Intent(this, DeviceDiscoveryActivity::class.java))
    }

    // ===================== AÇÕES - TOPO =====================

    /** Hoje: apenas feedback local. Futuramente: tvController.power() */
    private fun power() {
        executeAction(binding.btnPower, toastText = "Power", logMessage = "Power button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.openKeyboard() */
    private fun keyboard() {
        executeAction(binding.btnKeyboard, toastText = "123", logMessage = "Keyboard button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.openAssistant() */
    private fun assistant() {
        executeAction(binding.btnAssistant, toastText = "Assistente", logMessage = "Assistant button pressed")
    }

    // ===================== AÇÕES - D-PAD =====================

    /** Hoje: apenas feedback local. Futuramente: tvController.dpadUp() */
    private fun dpadUp() {
        executeAction(binding.btnDpadUp, toastText = "Cima", logMessage = "D-pad Up button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.dpadDown() */
    private fun dpadDown() {
        executeAction(binding.btnDpadDown, toastText = "Baixo", logMessage = "D-pad Down button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.dpadLeft() */
    private fun dpadLeft() {
        executeAction(binding.btnDpadLeft, toastText = "Esquerda", logMessage = "D-pad Left button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.dpadRight() */
    private fun dpadRight() {
        executeAction(binding.btnDpadRight, toastText = "Direita", logMessage = "D-pad Right button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.dpadOk() */
    private fun dpadOk() {
        executeAction(binding.btnDpadOk, toastText = "OK", logMessage = "D-pad OK button pressed")
    }

    // ===================== AÇÕES - CONTROLES DO MEIO =====================

    /** Hoje: apenas feedback local. Futuramente: tvController.back() */
    private fun back() {
        executeAction(binding.btnBack, toastText = "Voltar", logMessage = "Back button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.home() */
    private fun home() {
        executeAction(binding.btnHome, toastText = "Home", logMessage = "Home button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.playPause() */
    private fun playPause() {
        executeAction(binding.btnPlayPause, toastText = "Play/Pause", logMessage = "Play/Pause button pressed")
    }

    // ===================== AÇÕES - VOLUME E CANAL =====================

    /** Hoje: apenas feedback local. Futuramente: tvController.volumeUp() */
    private fun volumeUp() {
        executeAction(binding.btnVolumeUp, toastText = "Volume +", logMessage = "Volume Up button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.volumeDown() */
    private fun volumeDown() {
        executeAction(binding.btnVolumeDown, toastText = "Volume -", logMessage = "Volume Down button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.channelUp() */
    private fun channelUp() {
        executeAction(binding.btnChannelUp, toastText = "Canal +", logMessage = "Channel Up button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.channelDown() */
    private fun channelDown() {
        executeAction(binding.btnChannelDown, toastText = "Canal -", logMessage = "Channel Down button pressed")
    }

    // ===================== AÇÕES - STREAMING =====================

    /** Hoje: apenas feedback local. Futuramente: tvController.openNetflix() */
    private fun netflix() {
        executeAction(binding.btnNetflix, toastText = "Netflix", logMessage = "Netflix button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.openPrimeVideo() */
    private fun primeVideo() {
        executeAction(binding.btnPrimeVideo, toastText = "Prime Video", logMessage = "Prime Video button pressed")
    }

    /** Hoje: apenas feedback local. Futuramente: tvController.openGloboplay() */
    private fun globoplay() {
        executeAction(binding.btnGloboplay, toastText = "Globoplay", logMessage = "Globoplay button pressed")
    }

    // ===================== PAINEL DE DIAGNÓSTICO =====================

    /**
     * Registra a MainActivity como observadora do DiagnosticManager e, se já
     * existir uma TV salva, tenta reconectar automaticamente com ela -
     * como o token de pareamento já foi salvo no primeiro pareamento, isso
     * reconecta direto, sem exigir nova confirmação na TV.
     */
    private fun setupDiagnosticPanel() {
        DiagnosticManager.addListener(diagnosticListener)
        TvManager.getSavedDevice(applicationContext)?.let { device ->
            TvManager.connect(applicationContext, device, object : TvConnectionListener {
                override fun onConnected() { /* painel de diagnóstico já reflete o estado via DiagnosticManager */ }
                override fun onPairingRequired() { /* não deveria ocorrer numa reconexão com token salvo */ }
                override fun onError(message: String) { /* fica desconectado; usuário pode reconectar em Configurações */ }
            })
        }
    }

    /** Abre o painel se estiver fechado, ou fecha se estiver aberto. */
    private fun toggleDiagnosticPanel() {
        if (isDiagnosticPanelOpen) closeDiagnosticPanel() else openDiagnosticPanel()
    }

    private fun openDiagnosticPanel() {
        if (isDiagnosticPanelOpen) return
        isDiagnosticPanelOpen = true
        with(binding.panelDiagnostic) {
            animate().cancel()
            alpha = 0f
            translationY = -diagnosticSlideDistancePx
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(DIAGNOSTIC_ANIM_DURATION_MS)
                .start()
        }
    }

    private fun closeDiagnosticPanel() {
        if (!isDiagnosticPanelOpen) return
        isDiagnosticPanelOpen = false
        with(binding.panelDiagnostic) {
            animate().cancel()
            animate()
                .alpha(0f)
                .translationY(-diagnosticSlideDistancePx)
                .setDuration(DIAGNOSTIC_ANIM_DURATION_MS)
                .withEndAction { visibility = View.GONE }
                .start()
        }
    }

    /**
     * Único ponto que escreve nos TextViews do painel. Chamado sempre que o
     * DiagnosticManager notifica uma mudança de estado ou um novo log -
     * o painel não precisa ser fechado/reaberto para refletir alterações.
     */
    private fun renderDiagnostic(state: DiagnosticState, logs: List<DiagnosticLogEntry>) {
        binding.txtDiagnosticInfo.text = buildDiagnosticInfoText(state)
        binding.txtDiagnosticLogs.text = buildDiagnosticLogsText(logs)
        binding.scrollDiagnosticLogs.post {
            binding.scrollDiagnosticLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun buildDiagnosticInfoText(state: DiagnosticState): String = listOf(
        diagnosticLine("IP", state.ip ?: "—"),
        diagnosticLine("Marca", state.brand ?: "—"),
        diagnosticLine("Modelo", state.model ?: "—"),
        diagnosticLine("Sistema", formatDiagnosticOs(state.os)),
        diagnosticLine("Controlador", state.controllerName ?: "—"),
        diagnosticLine("Protocolo", formatDiagnosticProtocol(state.protocol)),
        diagnosticLine("Status", state.connectionStatus),
        diagnosticLine("Ping", state.pingMs?.let { "$it ms" } ?: "—"),
        diagnosticLine("Token", state.tokenMasked ?: "—"),
        diagnosticLine("Último comando", state.lastCommand ?: "—"),
        diagnosticLine("Resposta", state.lastResponse ?: "—"),
        diagnosticLine("Erro", state.lastError ?: "—")
    ).joinToString(separator = "\n")

    private fun buildDiagnosticLogsText(logs: List<DiagnosticLogEntry>): String {
        if (logs.isEmpty()) return "Nenhum evento registrado ainda."
        return logs.joinToString(separator = "\n") { entry -> "[${entry.timestamp}] ${entry.message}" }
    }

    /** Formata "Rótulo....................valor", alinhado em coluna monoespaçada. */
    private fun diagnosticLine(label: String, value: String): String {
        val dotsCount = (DIAGNOSTIC_LABEL_COLUMN_WIDTH - label.length).coerceAtLeast(1)
        return label + ".".repeat(dotsCount) + value
    }

    private fun formatDiagnosticOs(rawOs: String?): String = when (rawOs) {
        "TIZEN" -> "Tizen"
        "WEBOS" -> "webOS"
        "ANDROID_TV" -> "Android TV"
        "GOOGLE_TV" -> "Google TV"
        "ROKU_OS" -> "Roku OS"
        "FIRE_OS" -> "Fire OS"
        "VIDAA" -> "VIDAA"
        null, "UNKNOWN" -> "—"
        else -> rawOs
    }

    private fun formatDiagnosticProtocol(rawProtocol: String?): String = when (rawProtocol) {
        "SSDP" -> "SSDP (UPnP)"
        "MDNS" -> "mDNS"
        null -> "—"
        else -> rawProtocol
    }

    // ===================== HELPERS REUTILIZÁVEIS =====================

    /**
     * Ponto único de tratamento de clique: dispara haptic feedback, anima o
     * botão, mostra o Toast e registra a mensagem no Logcat.
     *
     * Centralizar aqui evita repetição nas funções de ação e facilita a
     * futura substituição do Toast por um comando real enviado à TV.
     */
    private fun executeAction(view: View, toastText: String, logMessage: String) {
        triggerHapticFeedback(view)
        animateClick(view)
        showToast(toastText)
        Log.d(TAG, logMessage)
    }

    /**
     * Aciona o haptic feedback nativo do Android. Se o aparelho não possuir
     * vibração (ou ela estiver desativada), performHapticFeedback apenas
     * retorna false, sem lançar exceção — nada precisa ser feito nesse caso.
     */
    private fun triggerHapticFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * Anima o botão: escala de 100% -> 95% -> 100% em ~100ms no total,
     * usando ViewPropertyAnimator (API nativa, sem dependências externas).
     */
    private fun animateClick(view: View) {
        view.animate()
            .scaleX(CLICK_ANIM_SCALE)
            .scaleY(CLICK_ANIM_SCALE)
            .setDuration(CLICK_ANIM_DURATION_MS)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(CLICK_ANIM_DURATION_MS)
                    .start()
            }
            .start()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}