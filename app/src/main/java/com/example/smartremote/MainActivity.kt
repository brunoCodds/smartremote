package com.example.smartremote

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.smartremote.manager.ReconnectionManager
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.ui.AppsBottomSheet
import com.example.smartremote.ui.RemoteKeypadBottomSheet
import com.example.smartremote.ui.TextInputBottomSheet
import java.util.Locale

/**
 * Tela única do Smart Remote (v1).
 *
 * Cada botão dispara feedback local (Toast + Log + animação + haptic) e,
 * quando aplicável, envia o comando real para a TV via TvManager
 * (sendRemoteKey/sendText) - sem conhecer nenhum detalhe de protocolo do
 * fabricante, que fica isolado em cada TvController concreto.
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

    /**
     * Resultado do reconhecimento de voz do Android (usado pelo botão
     * Assistente - ver [assistant]/[startVoiceRecognition]). Não controla
     * Bixby/Alexa/Google Assistant da TV - só reconhece a fala localmente
     * no aparelho e envia o TEXTO reconhecido para a TV via
     * TvManager.sendText(), mesmo caminho do teclado digitado.
     */
    private val voiceRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (spokenText.isNullOrBlank()) {
            showToast(getString(R.string.voice_no_speech_recognized))
            return@registerForActivityResult
        }

        TvManager.sendText(spokenText)
        showToast(getString(R.string.text_input_sent_toast))
    }

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

    override fun onStart() {
        super.onStart()
        // *** v0.9, item 1 ***: cobre tanto a abertura fria do app quanto
        // o retorno de background - reconecta proativamente com a última
        // TV conectada em vez de ficar passivo esperando um comando do
        // usuário para só aí descobrir que está desconectado.
        ReconnectionManager.startNetworkMonitor(applicationContext)
        TvManager.reconnectIfNeeded(applicationContext)
    }

    override fun onStop() {
        // Monitor de rede só precisa estar ativo com a tela em primeiro
        // plano - evita callback duplicado/vazado se onStart rodar de
        // novo antes de um onStop anterior ser processado.
        ReconnectionManager.stopNetworkMonitor()
        super.onStop()
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
            btnAbc.setOnClickListener { abc() }
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
            btnMute.setOnClickListener { mute() }
            btnChannelUp.setOnClickListener { channelUp() }
            btnChannelDown.setOnClickListener { channelDown() }

            // Streaming
            btnNetflix.setOnClickListener { netflix() }
            btnPrimeVideo.setOnClickListener { primeVideo() }
            btnGloboplay.setOnClickListener { globoplay() }
            btnApps.setOnClickListener { apps() }
        }
    }

    /** Abre a tela de descoberta/pareamento de Smart TVs na rede local. */
    private fun openDeviceDiscovery() {
        startActivity(Intent(this, DeviceDiscoveryActivity::class.java))
    }

    // ===================== AÇÕES - TOPO =====================

    /** Envia RemoteKey.POWER para a TV, além do feedback local. */
    private fun power() {
        TvManager.sendRemoteKey(RemoteKey.POWER)
        executeAction(binding.btnPower, toastText = "Power", logMessage = "Power button pressed")
    }

    /**
     * Abre o teclado numérico (BottomSheet) em vez de mandar uma tecla
     * diretamente - o "123" é um atalho de navegação da UI, não um
     * RemoteKey em si. Cada botão do BottomSheet chama TvManager por
     * conta própria (ver RemoteKeypadBottomSheet).
     */
    private fun keyboard() {
        triggerHapticFeedback(binding.btnKeyboard)
        RemoteKeypadBottomSheet().show(supportFragmentManager, RemoteKeypadBottomSheet.TAG)
    }

    /**
     * Atalho de UI que pula direto para a digitação de texto, sem passar
     * pelo teclado numérico primeiro - mesma tela ([TextInputBottomSheet])
     * que o botão "ABC" de dentro do RemoteKeypadBottomSheet já abre, e o
     * mesmo TvManager.sendText() usado pela entrada por voz. Não conhece
     * nada de Samsung - só abre a UI genérica de texto.
     */
    private fun abc() {
        triggerHapticFeedback(binding.btnAbc)
        TextInputBottomSheet().show(supportFragmentManager, TextInputBottomSheet.TAG)
    }

    /**
     * Não existe um comando Samsung confiável/documentado para abrir o
     * Bixby via WebSocket de forma consistente entre modelos - por isso
     * este botão foi reaproveitado para o reconhecimento de voz do
     * próprio Android (ver [startVoiceRecognition]), que envia o texto
     * reconhecido para a TV pelo mesmo mecanismo do teclado digitado.
     */
    private fun assistant() {
        triggerHapticFeedback(binding.btnAssistant)
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            voiceRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            // Nenhum app de reconhecimento de voz disponível no aparelho.
            showToast(getString(R.string.voice_not_available))
        }
    }

    // ===================== AÇÕES - D-PAD =====================

    /** Envia RemoteKey.UP para a TV, além do feedback local. */
    private fun dpadUp() {
        TvManager.sendRemoteKey(RemoteKey.UP)
        executeAction(binding.btnDpadUp, toastText = "Cima", logMessage = "D-pad Up button pressed")
    }

    /** Envia RemoteKey.DOWN para a TV, além do feedback local. */
    private fun dpadDown() {
        TvManager.sendRemoteKey(RemoteKey.DOWN)
        executeAction(binding.btnDpadDown, toastText = "Baixo", logMessage = "D-pad Down button pressed")
    }

    /** Envia RemoteKey.LEFT para a TV, além do feedback local. */
    private fun dpadLeft() {
        TvManager.sendRemoteKey(RemoteKey.LEFT)
        executeAction(binding.btnDpadLeft, toastText = "Esquerda", logMessage = "D-pad Left button pressed")
    }

    /** Envia RemoteKey.RIGHT para a TV, além do feedback local. */
    private fun dpadRight() {
        TvManager.sendRemoteKey(RemoteKey.RIGHT)
        executeAction(binding.btnDpadRight, toastText = "Direita", logMessage = "D-pad Right button pressed")
    }

    /** Envia RemoteKey.OK para a TV, além do feedback local. */
    private fun dpadOk() {
        TvManager.sendRemoteKey(RemoteKey.OK)
        executeAction(binding.btnDpadOk, toastText = "OK", logMessage = "D-pad OK button pressed")
    }

    // ===================== AÇÕES - CONTROLES DO MEIO =====================

    /** Envia RemoteKey.BACK para a TV, além do feedback local. */
    private fun back() {
        TvManager.sendRemoteKey(RemoteKey.BACK)
        executeAction(binding.btnBack, toastText = "Voltar", logMessage = "Back button pressed")
    }

    /** Envia RemoteKey.HOME para a TV, além do feedback local. */
    private fun home() {
        TvManager.sendRemoteKey(RemoteKey.HOME)
        executeAction(binding.btnHome, toastText = "Home", logMessage = "Home button pressed")
    }

    /** Envia RemoteKey.PLAY_PAUSE para a TV, além do feedback local. */
    private fun playPause() {
        TvManager.sendRemoteKey(RemoteKey.PLAY_PAUSE)
        executeAction(binding.btnPlayPause, toastText = "Play/Pause", logMessage = "Play/Pause button pressed")
    }

    // ===================== AÇÕES - VOLUME E CANAL =====================

    /** Envia RemoteKey.VOLUME_UP para a TV, além do feedback local. */
    private fun volumeUp() {
        TvManager.sendRemoteKey(RemoteKey.VOLUME_UP)
        executeAction(binding.btnVolumeUp, toastText = "Volume +", logMessage = "Volume Up button pressed")
    }

    /** Envia RemoteKey.VOLUME_DOWN para a TV, além do feedback local. */
    private fun volumeDown() {
        TvManager.sendRemoteKey(RemoteKey.VOLUME_DOWN)
        executeAction(binding.btnVolumeDown, toastText = "Volume -", logMessage = "Volume Down button pressed")
    }

    /** Envia RemoteKey.MUTE para a TV, além do feedback local. */
    private fun mute() {
        TvManager.sendRemoteKey(RemoteKey.MUTE)
        executeAction(binding.btnMute, toastText = "Mute", logMessage = "Mute button pressed")
    }

    /** Envia RemoteKey.CHANNEL_UP para a TV, além do feedback local. */
    private fun channelUp() {
        TvManager.sendRemoteKey(RemoteKey.CHANNEL_UP)
        executeAction(binding.btnChannelUp, toastText = "Canal +", logMessage = "Channel Up button pressed")
    }

    /** Envia RemoteKey.CHANNEL_DOWN para a TV, além do feedback local. */
    private fun channelDown() {
        TvManager.sendRemoteKey(RemoteKey.CHANNEL_DOWN)
        executeAction(binding.btnChannelDown, toastText = "Canal -", logMessage = "Channel Down button pressed")
    }

    // ===================== AÇÕES - STREAMING =====================

    /**
     * Envia RemoteKey.NETFLIX para a TV. Ainda não suportado por nenhum
     * TvController nesta fase (lançamento de app usa um mecanismo
     * diferente do protocolo - ms.channel.emit + app ID - fica para uma
     * fase futura dedicada a apps). Não trava o app, só registra "não
     * suportado" no diagnóstico.
     */
    private fun netflix() {
        TvManager.sendRemoteKey(RemoteKey.NETFLIX)
        executeAction(binding.btnNetflix, toastText = "Netflix", logMessage = "Netflix button pressed")
    }

    /** Envia RemoteKey.PRIME_VIDEO para a TV. Mesma observação de [netflix]. */
    private fun primeVideo() {
        TvManager.sendRemoteKey(RemoteKey.PRIME_VIDEO)
        executeAction(binding.btnPrimeVideo, toastText = "Prime Video", logMessage = "Prime Video button pressed")
    }

    /** Envia RemoteKey.GLOBOPLAY para a TV. Mesma observação de [netflix]. */
    private fun globoplay() {
        TvManager.sendRemoteKey(RemoteKey.GLOBOPLAY)
        executeAction(binding.btnGloboplay, toastText = "Globoplay", logMessage = "Globoplay button pressed")
    }

    /**
     * Abre a grade de apps (BottomSheet) em vez de mandar um RemoteKey
     * direto - "Apps" é um atalho de navegação da UI, igual [keyboard]/
     * [abc]. Cada card dentro do AppsBottomSheet chama TvManager.sendRemoteKey()
     * por conta própria com o app escolhido.
     */
    private fun apps() {
        triggerHapticFeedback(binding.btnApps)
        AppsBottomSheet().show(supportFragmentManager, AppsBottomSheet.TAG)
    }

    // ===================== PAINEL DE DIAGNÓSTICO =====================

    /**
     * Registra a MainActivity como observadora do DiagnosticManager e, se já
     * existir uma TV salva, tenta reconectar automaticamente com ela -
     * como o token de pareamento já foi salvo no primeiro pareamento, isso
     * reconecta direto, sem exigir nova confirmação na TV.
     *
     * *** CORREÇÃO ***: antes usava [TvManager.getSavedDevice], que
     * sempre retorna a PRIMEIRA TV da lista de pareadas, então trocar de
     * TV ativa (ex: parear a LG depois da Samsung) não tinha efeito
     * nenhum na reconexão automática do próximo lançamento do app - o
     * app sempre voltava para a primeira TV pareada, dando a impressão
     * de "a outra TV sumiu". [getLastConnectedDevice] usa a marcação
     * feita em TvManager.pairDevice() para reconectar sempre com a TV
     * que foi de fato usada por último.
     */
    /**
     * Registra a MainActivity como observadora do DiagnosticManager. A
     * tentativa de reconexão automática com a última TV conectada NÃO
     * acontece mais aqui - ver [onStart]/[TvManager.reconnectIfNeeded]
     * (*** v0.9, item 1 ***). Motivo: onCreate só roda na abertura "fria"
     * do app; onStart roda tanto na abertura fria quanto toda vez que o
     * app volta para o foreground vindo de background, que é justamente
     * o outro cenário descrito no pedido da v0.9 ("abrir o app, mesmo
     * com uma TV pareada e na mesma rede, ele não reconecta
     * automaticamente").
     */
    private fun setupDiagnosticPanel() {
        DiagnosticManager.addListener(diagnosticListener)
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
