package com.example.smartremote

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.databinding.ActivityMainBinding
import com.example.smartremote.diagnostic.DeepDiagnosticActivity
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.diagnostic.DiagnosticState
import com.example.smartremote.discovery.DeviceDiscoveryActivity
import com.example.smartremote.faq.FaqActivity
import com.example.smartremote.manager.ReconnectionManager
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.ui.AppsBottomSheet
import com.example.smartremote.ui.RemoteKeypadBottomSheet
import com.example.smartremote.ui.TextInputBottomSheet
import com.example.smartremote.util.Constants
import com.example.smartremote.util.LanguageManager
import com.example.smartremote.util.UserPreferences
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

        // ===== v0.9.3, item 1 - Indicador "Reconectando" =====
        private const val RECONNECT_FADE_DURATION_MS = 200L
        private const val RECONNECT_ROTATION_DURATION_MS = 900L

        // ===== v0.9.4 - Modo cursor/mouse =====
        /** Fator de escala do delta bruto de arrasto (em px de tela) antes de mandar pra TV - ajuste empírico, sem valor "certo" a priori (ver prompt da v0.9.4). */
        private const val CURSOR_SENSITIVITY = 1.5f
        /** Intervalo mínimo entre dois comandos de move consecutivos - agrupa o movimento acumulado nesse meio-tempo em vez de mandar um comando por pixel.
         *  *** AJUSTADO (pós-v0.9.4, feedback de suavidade) ***: era 40ms: reduzido pra distribuir o mesmo movimento total em pacotes menores e mais frequentes,
         *  em vez de saltos grandes e espaçados - TVs sob carga (apps de streaming tipo YouTube/Netflix competindo por CPU/GPU) têm mais chance de conseguir
         *  processar/renderizar pacotes pequenos de forma fluida do que absorver um salto grande de uma vez. Ajustável se, na prática, sobrecarregar a TV. */
        private const val CURSOR_MOVE_THROTTLE_MS = 20L
        /** Distância total percorrida (em dp) abaixo da qual um gesto é tratado como toque/clique em vez de arrasto. */
        private const val CURSOR_TAP_MAX_DISTANCE_DP = 12f
    }

    /**
     * Recebe as atualizações do DiagnosticManager e repassa tanto para
     * renderDiagnostic() (painel de debug) quanto para
     * updateReconnectIndicator() (*** NOVO - v0.9.3, item 1 ***) - os dois
     * lêem do mesmo DiagnosticState, só mudam de apresentação.
     *
     * *** v0.9.3 (correção pós-lançamento) ***: o parâmetro `logs` não é
     * mais usado aqui - o stream de log foi removido do painel simples a
     * pedido explícito (só os blocos TV/Última atividade continuam).
     * Continua chegando porque é a mesma assinatura de
     * [DiagnosticManager.Listener] usada por
     * [com.example.smartremote.diagnostic.DeepDiagnosticActivity], que
     * ainda precisa dele.
     */
    private val diagnosticListener = DiagnosticManager.Listener { state, _ ->
        renderDiagnostic(state)
        updateReconnectIndicator(state)
        updateCursorToggleAvailability() // *** NOVO - v0.9.4 ***: suporte a cursor pode mudar (troca de TV) sem reabrir o app
    }

    private var isDiagnosticPanelOpen = false

    /** *** NOVO - v0.9.3, item 1 ***: evita reabrir/reanimar o indicador à toa em atualizações que não mudam o estado ligado/desligado (ex: um novo log chegando enquanto já está visível). */
    private var isReconnectIndicatorVisible = false

    /** *** NOVO - v0.9.3, item 1 ***: referência para poder cancelar a rotação (bateria/CPU) quando o indicador é escondido. */
    private var reconnectRotationAnimator: ObjectAnimator? = null

    // ===== v0.9.4 - Modo cursor/mouse =====
    /** `true` enquanto a superfície de toque (cursorTouchpad) está ativa em vez do D-pad tradicional. */
    private var isCursorModeActive = false
    /** Timestamp (uptimeMillis) do último comando de move efetivamente enviado - base do throttle. */
    private var lastCursorMoveSentAt = 0L
    /** Último ponto bruto (rawX/rawY) recebido no gesto atual - usado para calcular o delta do próximo ACTION_MOVE. */
    private var cursorLastX = 0f
    private var cursorLastY = 0f
    /** Delta acumulado (ainda não enviado) desde o último comando de move - zerado a cada envio. */
    private var cursorPendingDx = 0f
    private var cursorPendingDy = 0f
    /** Distância total (em px) percorrida desde o ACTION_DOWN do gesto atual - usada para decidir tap vs. arrasto no ACTION_UP. */
    private var cursorTotalDragDistance = 0f

    private val cursorTapMaxDistancePx: Float by lazy {
        CURSOR_TAP_MAX_DISTANCE_DP * resources.displayMetrics.density
    }

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
        applyKeepScreenOnPreference() // *** NOVO - v0.9.5 ***
        setupClickListeners()
        setupDiagnosticPanel()
        setupDrawer() // *** NOVO - v0.9.3, item 3 ***
        setupCursorMode() // *** NOVO - v0.9.4 ***
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
        stopReconnectRotation() // *** NOVO - v0.9.3, item 1 ***: evita animator rodando após a Activity morrer
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

    /**
     * *** NOVO - v0.9.5 (configurações de auxílio ao usuário) ***: aplica
     * FLAG_KEEP_SCREEN_ON conforme a preferência salva (padrão `true` -
     * ver [UserPreferences.isKeepScreenOnEnabled]). Chamado em onCreate() e
     * de novo a cada toque no toggle do drawer (ver setupDrawer()), pra
     * refletir a mudança imediatamente sem precisar reabrir a tela.
     */
    private fun applyKeepScreenOnPreference() {
        if (UserPreferences.isKeepScreenOnEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Centraliza a configuração de todos os cliques do controle remoto. */
    private fun setupClickListeners() {
        with(binding) {
            // Diagnóstico (painel de debug, apenas dev)
            btnInfo.setOnClickListener { toggleDiagnosticPanel() }

            // Menu lateral (v0.9.3, item 3) - antes ia direto para a
            // descoberta; agora abre o drawer, que tem "Pareamento de TV"
            // como um dos itens (ver setupDrawer()).
            btnSettings.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

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

    // ===================== MENU LATERAL (v0.9.3, item 3) =====================

    /**
     * Wiring dos itens do drawer (ver res/layout/drawer_content.xml,
     * incluído em activity_main.xml via `<include>` com id
     * `drawerContent` - os ids internos do include são acessados como
     * `binding.drawerContent.<id>`, padrão normal de ViewBinding com
     * `<include>`).
     *
     * Cada item fecha o drawer (`closeDrawer`) antes de disparar sua ação -
     * evita a sensação de "menu ainda meio aberto por cima" ao voltar de
     * uma Activity nova, e no caso do diálogo de idioma evita o dialog
     * abrindo com o drawer ainda visível atrás dele.
     */
    private fun setupDrawer() {
        // *** NOVO - v0.9.3, item 3 ***: sem isso, o botão "voltar" do
        // Android com o drawer aberto sairia da MainActivity em vez de só
        // fechar o menu (comportamento padrão quando nada intercepta o
        // back). O callback começa desabilitado e só liga/desliga junto
        // com o estado real do drawer (DrawerListener abaixo) - assim ele
        // nunca "rouba" o back em telas/estados onde não devia.
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDrawer()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit
            override fun onDrawerStateChanged(newState: Int) = Unit
            override fun onDrawerOpened(drawerView: View) {
                backCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                backCallback.isEnabled = false
            }
        })

        with(binding.drawerContent) {
            itemPairing.setOnClickListener {
                closeDrawer()
                openDeviceDiscovery()
            }
            itemDeepDiagnostic.setOnClickListener {
                closeDrawer()
                startActivity(Intent(this@MainActivity, DeepDiagnosticActivity::class.java))
            }
            itemFaq.setOnClickListener {
                closeDrawer()
                startActivity(Intent(this@MainActivity, FaqActivity::class.java))
            }
            itemShare.setOnClickListener {
                closeDrawer()
                shareApp()
            }
            itemLanguage.setOnClickListener {
                closeDrawer()
                showLanguagePicker()
            }
            // *** NOVO - v0.9.5 (configurações de auxílio ao usuário) ***:
            // switches inicializados com o valor salvo, e o clique é na
            // LINHA inteira (o MaterialSwitch em si é não-clicável, ver
            // drawer_content.xml) - NÃO fecha o drawer, diferente dos
            // itens acima, pra dar pra mexer nos dois toggles em
            // sequência sem reabrir o menu.
            switchKeepScreenOn.isChecked = UserPreferences.isKeepScreenOnEnabled(this@MainActivity)
            itemKeepScreenOn.setOnClickListener {
                val enabled = !switchKeepScreenOn.isChecked
                switchKeepScreenOn.isChecked = enabled
                UserPreferences.setKeepScreenOnEnabled(this@MainActivity, enabled)
                applyKeepScreenOnPreference()
            }
            switchVibrationFeedback.isChecked = UserPreferences.isVibrationEnabled(this@MainActivity)
            itemVibrationFeedback.setOnClickListener {
                val enabled = !switchVibrationFeedback.isChecked
                switchVibrationFeedback.isChecked = enabled
                UserPreferences.setVibrationEnabled(this@MainActivity, enabled)
            }
            itemGithub.setOnClickListener {
                closeDrawer()
                openExternalLink(Constants.AUTHOR_GITHUB_URL)
            }
            itemLinkedin.setOnClickListener {
                closeDrawer()
                openExternalLink(Constants.AUTHOR_LINKEDIN_URL)
            }
        }
    }

    private fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    /**
     * `Intent.ACTION_SEND` (texto simples) com o link do repositório GitHub
     * - ver KDoc de [Constants.SHARE_APP_URL] para o porquê de ser esse
     * link e não um de loja/Play Store por enquanto.
     */
    private fun shareApp() {
        val shareText = getString(R.string.share_app_text, Constants.SHARE_APP_URL)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_app_chooser_title)))
    }

    /** Abre um link externo (GitHub/LinkedIn do rodapé do menu) no navegador padrão do aparelho. */
    private fun openExternalLink(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /**
     * Diálogo simples com as 4 opções fixas de idioma (ver
     * [LanguageManager] para o porquê de não haver uma opção "padrão do
     * sistema"). A opção correspondente ao idioma atual já vem marcada.
     * Ao escolher, [LanguageManager.setLanguage] já cuida de recriar as
     * Activities visíveis com o novo idioma - não é preciso `recreate()`
     * manual aqui.
     */
    private fun showLanguagePicker() {
        val languages = LanguageManager.AppLanguage.values()
        val labels = arrayOf(
            getString(R.string.language_option_pt),
            getString(R.string.language_option_en),
            getString(R.string.language_option_es),
            getString(R.string.language_option_fr)
        )
        val currentIndex = languages.indexOf(LanguageManager.getCurrentLanguage())

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                LanguageManager.setLanguage(this, languages[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    // ===================== MODO CURSOR/MOUSE (v0.9.4) =====================

    /**
     * *** NOVO - v0.9.4 ***: configura o botão de alternância D-pad/cursor
     * e o OnTouchListener da superfície de toque (binding.cursorTouchpad).
     * Chamado uma vez em onCreate(), junto dos outros setupXxx().
     */
    private fun setupCursorMode() {
        updateCursorToggleAvailability()
        binding.btnCursorToggle.setOnClickListener { toggleCursorMode() }
        binding.cursorTouchpad.setOnTouchListener { view, event -> handleCursorTouch(view, event) }
    }

    /**
     * Habilita/desabilita o botão de alternância conforme a TV atualmente
     * conectada suporta cursor ou não (ver TvController.supportsCursorMode()
     * via TvManager.isCursorModeSupported()). Chamado uma vez em
     * setupCursorMode() e de novo a cada atualização de diagnóstico (ver
     * diagnosticListener) - o suporte pode mudar em runtime (ex: usuário
     * troca de uma TV LG para uma Android TV sem fechar o app).
     *
     * Requisito explícito do prompt: o botão nunca deve aparecer clicável
     * e falhar silenciosamente ao tocar - por isso isEnabled + alpha
     * reduzido, não só uma checagem dentro do clique.
     *
     * Se o modo cursor estava ativo e a TV deixou de suportar, força a
     * volta pro D-pad - nunca deixa a superfície de toque ativa sem
     * suporte real por trás dela.
     */
    private fun updateCursorToggleAvailability() {
        val supported = TvManager.isCursorModeSupported()
        binding.btnCursorToggle.isEnabled = supported
        binding.btnCursorToggle.alpha = if (supported) 1f else 0.35f
        if (!supported && isCursorModeActive) {
            setCursorModeActive(false)
        }
    }

    private fun toggleCursorMode() {
        setCursorModeActive(!isCursorModeActive)
    }

    /**
     * Alterna a área do dpadContainer entre D-pad ([binding.dpadButtonsGroup])
     * e superfície de cursor ([binding.cursorTouchpad] + [binding.cursorTouchpadHint])
     * - reaproveita a mesma região da tela trocando de conteúdo, em vez de
     * abrir uma tela nova (requisito explícito do prompt). O fundo do
     * botão de alternância também muda (mesmo drawable "selecionado" já
     * usado pelo botão OK do D-pad) para indicar visualmente o modo ativo.
     */
    private fun setCursorModeActive(active: Boolean) {
        isCursorModeActive = active
        // *** AJUSTE - v0.9.5 ***: vibração só ao ENTRAR no modo cursor,
        // não ao sair - pedido explícito, pra não competir com a vibração
        // do clique dentro do modo cursor nem virar ruído a cada toggle.
        if (active) {
            triggerHapticFeedback(binding.btnCursorToggle)
        }
        binding.dpadButtonsGroup.visibility = if (active) View.GONE else View.VISIBLE
        binding.cursorTouchpad.visibility = if (active) View.VISIBLE else View.GONE
        binding.cursorTouchpadHint.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnCursorToggle.background = ContextCompat.getDrawable(
            this,
            if (active) R.drawable.fluent_bg_button_glass_circle_selected else R.drawable.fluent_bg_button_glass_circle
        )
        binding.btnCursorToggle.contentDescription = getString(
            if (active) R.string.desc_cursor_mode_toggle_exit else R.string.desc_cursor_mode_toggle_enter
        )
        showToast(getString(if (active) R.string.cursor_mode_entered_toast else R.string.cursor_mode_exited_toast))
    }

    /**
     * Trata o gesto na superfície de toque do modo cursor:
     * - ACTION_DOWN: guarda o ponto inicial, zera distância acumulada e
     *   delta pendente.
     * - ACTION_MOVE: primeiro processa os pontos HISTÓRICOS do evento
     *   ([MotionEvent.getHistoricalX]/[getHistoricalY] - ver comentário
     *   abaixo), depois o ponto atual; soma tudo ao delta pendente (não
     *   substitui - ver KDoc de [cursorPendingDx]) e à distância total
     *   percorrida (para diferenciar tap de arrasto no ACTION_UP). Só
     *   manda de fato um comando de rede (TvManager.sendCursorMove) quando
     *   o throttle ([CURSOR_MOVE_THROTTLE_MS]) permitir - nesse momento
     *   manda a SOMA acumulada desde o último envio (requisito explícito
     *   do prompt original: "agrupe o movimento acumulado", não descartar
     *   os pixels do meio) e preserva a fração não-enviada (ver comentário
     *   no ponto de envio) em vez de descartá-la.
     * - ACTION_UP/ACTION_CANCEL: se a distância total percorrida desde o
     *   DOWN for menor que [cursorTapMaxDistancePx], trata como toque
     *   curto (clique) em vez de arrasto - TvManager.sendCursorClick().
     *
     * Usa coordenadas LOCAIS da view (event.x/y), não rawX/rawY como na
     * v0.9.4 original - necessário porque os pontos históricos só existem
     * em coordenada local (não existe getHistoricalRawX pré-API 34). Como
     * só usamos deltas (não posição absoluta) e a view não se move durante
     * o gesto, a troca não muda o resultado.
     *
     * Retorna `true` para os casos tratados (continua recebendo os
     * próximos eventos do mesmo gesto); `false` para qualquer outra ação
     * não tratada.
     */
    private fun handleCursorTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cursorLastX = event.x
                cursorLastY = event.y
                cursorTotalDragDistance = 0f
                cursorPendingDx = 0f
                cursorPendingDy = 0f
                lastCursorMoveSentAt = 0L
            }

            MotionEvent.ACTION_MOVE -> {
                // *** AJUSTE (pós-v0.9.4, feedback de suavidade) ***: o
                // Android agrupa (batching) vários toques físicos num
                // único MotionEvent quando o sistema está ocupado - sem
                // processar o histórico, esses pontos intermediários eram
                // descartados e o gesto parecia "pular" em vez de deslizar,
                // efeito que fica mais visível justamente quando algo (no
                // celular ou na TV) está sob carga - o mesmo cenário
                // relatado com apps de streaming abertos na TV.
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    val hdx = hx - cursorLastX
                    val hdy = hy - cursorLastY
                    cursorPendingDx += hdx
                    cursorPendingDy += hdy
                    cursorTotalDragDistance += kotlin.math.hypot(hdx, hdy)
                    cursorLastX = hx
                    cursorLastY = hy
                }

                val dx = event.x - cursorLastX
                val dy = event.y - cursorLastY
                cursorLastX = event.x
                cursorLastY = event.y
                cursorTotalDragDistance += kotlin.math.hypot(dx, dy)
                cursorPendingDx += dx
                cursorPendingDy += dy

                val now = SystemClock.uptimeMillis()
                if (now - lastCursorMoveSentAt >= CURSOR_MOVE_THROTTLE_MS) {
                    lastCursorMoveSentAt = now
                    val scaledDx = cursorPendingDx * CURSOR_SENSITIVITY
                    val scaledDy = cursorPendingDy * CURSOR_SENSITIVITY
                    val intDx = scaledDx.toInt()
                    val intDy = scaledDy.toInt()
                    if (intDx != 0 || intDy != 0) {
                        TvManager.sendCursorMove(intDx, intDy)
                    }
                    // *** CORREÇÃO (pós-v0.9.4, feedback de suavidade) ***:
                    // antes zerava pendingDx/Dy pro valor bruto acumulado,
                    // descartando a fração de pixel que não coube no
                    // Int enviado. Em arrastos lentos (delta bruto menor
                    // que 1px por leva) isso fazia o cursor "grudar" -
                    // nenhum movimento nunca acumulava o suficiente pra
                    // virar 1 pixel inteiro depois de multiplicar pela
                    // sensibilidade. Agora guarda essa sobra (já
                    // convertida de volta pra espaço de tela, dividindo
                    // pela sensibilidade) pra somar na próxima leva, em
                    // vez de simplesmente perdê-la.
                    cursorPendingDx = (scaledDx - intDx) / CURSOR_SENSITIVITY
                    cursorPendingDy = (scaledDy - intDy) / CURSOR_SENSITIVITY
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    cursorTotalDragDistance < cursorTapMaxDistancePx
                ) {
                    triggerHapticFeedback(view)
                    view.performClick() // acessibilidade (TalkBack) - view continua tratando o clique de verdade aqui, não em setOnClickListener
                    TvManager.sendCursorClick()
                }
            }

            else -> return false
        }
        return true
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
     *
     * *** v0.9.3 (correção pós-lançamento) ***: não configura mais nenhum
     * RecyclerView aqui - o stream de log foi removido do painel simples
     * (ver KDoc de [diagnosticListener] e o layout de `panelDiagnostic`
     * em activity_main.xml).
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
     * Único ponto que escreve nos TextViews/RecyclerView do painel simples.
     * Chamado sempre que o DiagnosticManager notifica uma mudança de
     * estado ou um novo log - o painel não precisa ser fechado/reaberto
     * para refletir alterações.
     *
     * *** v0.9.3 (correção pós-lançamento) - painel simplificado para
     * leigos ***: só 2 blocos - "TV" (Nome + Status) e "Última atividade"
     * (Erro + Ping). O stream de log cru também foi removido (ver KDoc de
     * [diagnosticListener]). Os campos técnicos (IP, protocolo, nome da
     * classe do controller, token, passos de log) não desapareceram do
     * app - continuam disponíveis por inteiro no Diagnóstico Aprofundado
     * (ver [com.example.smartremote.diagnostic.DeepDiagnosticActivity]),
     * só saíram deste painel porque ele é voltado a "minha TV está
     * funcionando?", não a depuração de protocolo.
     */
    private fun renderDiagnostic(state: DiagnosticState) {
        binding.txtDiagnosticTv.text = buildDiagnosticTvText(state)
        binding.txtDiagnosticActivity.text = buildDiagnosticActivityText(state)
    }

    /**
     * Nome legível da TV (reaproveita [buildReconnectDeviceLabel], mesma
     * lógica "Marca Modelo" já usada no indicador de reconexão - item 1)
     * + status da conexão, já traduzido (ver [DiagnosticState.connectionStatus],
     * que os TvControllers agora setam via `context.getString(R.string.status_*)`).
     */
    private fun buildDiagnosticTvText(state: DiagnosticState): String {
        val na = getString(R.string.diagnostic_value_unavailable)
        return listOf(
            diagnosticLine(getString(R.string.diagnostic_label_name), buildReconnectDeviceLabel(state) ?: na),
            diagnosticLine(getString(R.string.diagnostic_label_status), state.connectionStatus)
        ).joinToString(separator = "\n")
    }

    /**
     * *** CORREÇÃO - v0.9.3 (pós-lançamento) ***
     * - "Ping" nunca era medido por NENHUM fabricante - `DiagnosticManager.setPing()`
     *   existia mas nada chamava. Corrigido com
     *   [com.example.smartremote.manager.PingMonitor], que mede o tempo de
     *   conexão TCP até a porta de controle da TV a cada 10s enquanto
     *   conectado.
     * - "Último comando" e "Resposta" foram REMOVIDOS deste painel a
     *   pedido explícito: são nomes técnicos de protocolo (ex: "KEY_HOME",
     *   "KEY_VOLUP") que não significam nada para um usuário comum e só
     *   ocupavam espaço de tela. `DiagnosticManager.setLastCommand()`/
     *   `setLastResponse()` continuam sendo chamados normalmente (o dado
     *   não deixou de ser coletado) - só não aparecem mais AQUI. Quem
     *   quiser ver esse detalhe cru continua tendo o Diagnóstico
     *   Aprofundado (item 4), que mostra esses dois campos por inteiro.
     */
    private fun buildDiagnosticActivityText(state: DiagnosticState): String {
        val na = getString(R.string.diagnostic_value_unavailable)
        return listOf(
            diagnosticLine(getString(R.string.diagnostic_label_last_error), state.lastError ?: na),
            diagnosticLine(getString(R.string.diagnostic_label_ping), state.pingMs?.let { getString(R.string.diagnostic_value_ping_format, it) } ?: na)
        ).joinToString(separator = "\n")
    }

    /** Formata "Rótulo....................valor", alinhado em coluna monoespaçada. */
    private fun diagnosticLine(label: String, value: String): String {
        val dotsCount = (DIAGNOSTIC_LABEL_COLUMN_WIDTH - label.length).coerceAtLeast(1)
        return label + ".".repeat(dotsCount) + value
    }

    // ===================== INDICADOR "RECONECTANDO" (v0.9.3, item 1) =====================

    /**
     * Liga/desliga o indicador "Reconectando a {TV}..." a partir de
     * [DiagnosticState.isAutoReconnecting]. Chamado a cada atualização do
     * DiagnosticManager (mesmo listener do painel de debug), mas só de
     * fato mexe na UI quando o valor de [DiagnosticState.isAutoReconnecting]
     * MUDOU em relação ao que já estava sendo exibido - evita reiniciar a
     * animação de rotação ou reanimar o fade a cada novo log que chega
     * durante uma reconexão já em andamento.
     */
    private fun updateReconnectIndicator(state: DiagnosticState) {
        if (state.isAutoReconnecting == isReconnectIndicatorVisible) {
            // Já está no estado certo - só atualiza o texto (o nome/marca
            // da TV pode ter chegado um instante depois do flag ligar).
            if (state.isAutoReconnecting) {
                binding.txtReconnecting.text = buildReconnectText(state)
            }
            return
        }

        if (state.isAutoReconnecting) {
            binding.txtReconnecting.text = buildReconnectText(state)
            showReconnectIndicator()
        } else {
            hideReconnectIndicator()
        }
    }

    /**
     * Mostra o indicador com fade-in (mesmo padrão de duração/curva usado em
     * [openDiagnosticPanel], mas só com alpha - o indicador não desliza,
     * só aparece/desaparece no lugar) e inicia a rotação contínua do ícone.
     */
    private fun showReconnectIndicator() {
        isReconnectIndicatorVisible = true
        with(binding.containerReconnecting) {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(RECONNECT_FADE_DURATION_MS).start()
        }
        startReconnectRotation()
    }

    /**
     * Esconde o indicador com fade-out e PARA a animação de rotação -
     * requisito explícito do item 1: não gastar bateria/CPU girando um
     * ícone invisível em segundo plano.
     */
    private fun hideReconnectIndicator() {
        isReconnectIndicatorVisible = false
        with(binding.containerReconnecting) {
            animate().cancel()
            animate()
                .alpha(0f)
                .setDuration(RECONNECT_FADE_DURATION_MS)
                .withEndAction { visibility = View.GONE }
                .start()
        }
        stopReconnectRotation()
    }

    private fun startReconnectRotation() {
        if (reconnectRotationAnimator?.isRunning == true) return
        reconnectRotationAnimator = ObjectAnimator.ofFloat(binding.imgReconnecting, View.ROTATION, 0f, 360f).apply {
            duration = RECONNECT_ROTATION_DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopReconnectRotation() {
        reconnectRotationAnimator?.cancel()
        reconnectRotationAnimator = null
        binding.imgReconnecting.rotation = 0f
    }

    /**
     * Formato do nome exibido: "Marca Modelo" (ex: "Samsung Q60") quando os
     * dois estiverem disponíveis - mais legível que só o IP ou só a marca.
     * Cai para marca OU modelo isolado se só um dos dois vier preenchido, e
     * para o [DiagnosticState.name] (nome bruto salvo na descoberta) como
     * último recurso antes de desistir e usar a versão genérica da string
     * ("Reconectando..." sem nome nenhum), conforme pedido no item 1.
     */
    private fun buildReconnectDeviceLabel(state: DiagnosticState): String? {
        val brand = state.brand?.trim()?.takeIf { it.isNotEmpty() }
        val model = state.model?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            brand != null && model != null -> "$brand $model"
            brand != null -> brand
            model != null -> model
            else -> state.name?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun buildReconnectText(state: DiagnosticState): String {
        val label = buildReconnectDeviceLabel(state)
        return if (label != null) {
            getString(R.string.reconnecting_with_name, label)
        } else {
            getString(R.string.reconnecting_generic)
        }
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
    /** *** NOVO - v0.9.5 ***: respeita o toggle "Feedback de vibração" do drawer (ver UserPreferences.isVibrationEnabled) - único ponto de saída pra TODAS as vibrações do app, então desligar aqui desliga em todo lugar de uma vez. */
    private fun triggerHapticFeedback(view: View) {
        if (!UserPreferences.isVibrationEnabled(this)) return
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
