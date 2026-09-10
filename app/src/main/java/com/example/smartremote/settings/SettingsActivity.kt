package com.example.smartremote.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.smartremote.databinding.ActivitySettingsBinding

/**
 * *** NOVO - v0.9.6, item 1 (Tela de Configurações) ***
 *
 * Tela cheia acessada pelo menu lateral, abaixo de "Mudar idioma" - mesmo
 * padrão visual/de navegação já usado por [com.example.smartremote.faq.FaqActivity]
 * e [com.example.smartremote.diagnostic.DeepDiagnosticActivity] (toolbar com
 * back, tema `Theme.SmartRemote`, sem inventar navegação nova).
 *
 * Por enquanto só a navegação em si (item 1 da sequência combinada) - o
 * corpo da tela fica em branco/placeholder. Os itens de verdade (sensibilidade
 * do cursor, reconexão automática, confirmação de power-off, sobre,
 * permissões, visibilidade/tamanho/posição dos botões, resetar) entram um de
 * cada vez nos próximos itens, cada um validado antes do seguinte.
 *
 * Os dois toggles de auxílio (manter tela ligada, feedback de vibração) NÃO
 * entram aqui - ficam onde estão, no rodapé do drawer (decisão explícita,
 * ver [com.example.smartremote.util.UserPreferences]).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
