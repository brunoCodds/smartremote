package com.example.smartremote

import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.smartremote.databinding.ActivityMainBinding

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableFullscreenMode()
        setupClickListeners()
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
            // Topo
            btnPower.setOnClickListener { power() }
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