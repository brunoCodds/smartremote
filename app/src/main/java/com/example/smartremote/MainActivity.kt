package com.example.smartremote

import android.os.Bundle
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
 * com um Toast de feedback. A comunicação real com a Smart TV será
 * adicionada em uma versão futura.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
            btnPower.setOnClickListener { onRemoteButtonClicked("Power") }
            btnKeyboard.setOnClickListener { onRemoteButtonClicked("123") }
            btnAssistant.setOnClickListener { onRemoteButtonClicked("Assistente") }

            // D-pad
            btnDpadUp.setOnClickListener { onRemoteButtonClicked("Cima") }
            btnDpadDown.setOnClickListener { onRemoteButtonClicked("Baixo") }
            btnDpadLeft.setOnClickListener { onRemoteButtonClicked("Esquerda") }
            btnDpadRight.setOnClickListener { onRemoteButtonClicked("Direita") }
            btnDpadOk.setOnClickListener { onRemoteButtonClicked("OK") }

            // Controles do meio
            btnBack.setOnClickListener { onRemoteButtonClicked("Voltar") }
            btnHome.setOnClickListener { onRemoteButtonClicked("Home") }
            btnPlayPause.setOnClickListener { onRemoteButtonClicked("Play/Pause") }

            // Volume e canal
            btnVolumeUp.setOnClickListener { onRemoteButtonClicked("Volume +") }
            btnVolumeDown.setOnClickListener { onRemoteButtonClicked("Volume -") }
            btnChannelUp.setOnClickListener { onRemoteButtonClicked("Canal +") }
            btnChannelDown.setOnClickListener { onRemoteButtonClicked("Canal -") }

            // Streaming
            btnNetflix.setOnClickListener { onRemoteButtonClicked("Netflix") }
            btnPrimeVideo.setOnClickListener { onRemoteButtonClicked("Prime Video") }
            btnGloboplay.setOnClickListener { onRemoteButtonClicked("Globoplay") }
        }
    }

    /**
     * Ponto único de tratamento de clique. Em uma versão futura, aqui entrará
     * o envio real do comando para a TV (ex: via rede local).
     */
    private fun onRemoteButtonClicked(buttonName: String) {
        showToast("$buttonName pressionado")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}