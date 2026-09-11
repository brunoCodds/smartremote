package com.example.smartremote.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartremote.R
import com.example.smartremote.databinding.ActivitySettingsBinding
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.ui.AppCatalog
import com.example.smartremote.util.UserPreferences

/**
 * *** NOVO - v0.9.6, item 1 (Tela de Configurações) ***
 *
 * Tela cheia acessada pelo menu lateral, abaixo de "Mudar idioma" - mesmo
 * padrão visual/de navegação já usado por [com.example.smartremote.faq.FaqActivity]
 * e [com.example.smartremote.diagnostic.DeepDiagnosticActivity] (toolbar com
 * back, tema `Theme.SmartRemote`, sem inventar navegação nova).
 *
 * Os itens entram um de cada vez, cada um validado antes do seguinte:
 * - item 1: navegação (esta classe, tela em branco) - concluído.
 * - item 2: sensibilidade do cursor (slider) - concluído, ver [setupCursorSensitivity].
 * - item 3: reconexão automática (toggle) - concluído, ver [setupAutoReconnect].
 * - item 4: confirmação de power-off (toggle) - concluído, ver [setupPowerOffConfirm]
 *   (o diálogo em si mora em `MainActivity.power()`, esta tela só grava o toggle).
 * - item 5: sobre (versão + link do GitHub) - concluído, ver [setupAbout].
 * - item 7: quais botões aparecem (toggles) + apps preferidos (picker dos
 *   3 atalhos de rowStreaming) - concluído, ver [setupButtonVisibility] e
 *   [setupPreferredApps]. `MainActivity.applyButtonVisibilityPreferences()`/
 *   `applyPreferredApps()` são quem realmente lê essas preferências.
 * - item 10: restaurar padrão - concluído, ver [setupResetToDefaults] (por
 *   último de propósito, só cobre os itens que já existem até agora).
 * - pendentes (aguardando decisão/confirmação antes de implementar, ver
 *   documento de planejamento da v0.9.6): permissões (item 6, adiado -
 *   ver conversa), tamanho dos botões (item 8), posição dos botões
 *   (item 9).
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

        setupCursorSensitivity()
        setupAutoReconnect()
        setupPowerOffConfirm()
        setupAbout()
        setupButtonVisibility()
        setupPreferredApps()
        setupResetToDefaults()
    }

    /**
     * *** NOVO - v0.9.6, item 2 ***: substitui a constante fixa
     * `CURSOR_SENSITIVITY` que existia em `MainActivity` por um valor
     * configurável aqui, persistido via [UserPreferences]. O slider já
     * nasce nos mesmos limites que [UserPreferences.setCursorSensitivity]
     * aplica ao gravar (0.5 a 3.0, passo de 0.1) - não dá pra escolher um
     * valor fora desse intervalo pela própria UI.
     */
    private fun setupCursorSensitivity() {
        val current = UserPreferences.getCursorSensitivity(this)
            .coerceIn(UserPreferences.CURSOR_SENSITIVITY_MIN, UserPreferences.CURSOR_SENSITIVITY_MAX)

        binding.sliderCursorSensitivity.value = current
        updateCursorSensitivityLabel(current)

        // clearOnChangeListeners() evita empilhar listener duplicado quando
        // esta função roda de novo (ver setupResetToDefaults) - addOnChangeListener
        // ACRESCENTA um listener novo a cada chamada, não substitui o anterior.
        binding.sliderCursorSensitivity.clearOnChangeListeners()
        binding.sliderCursorSensitivity.addOnChangeListener { _, value, fromUser ->
            updateCursorSensitivityLabel(value)
            if (fromUser) {
                UserPreferences.setCursorSensitivity(this, value)
            }
        }
    }

    private fun updateCursorSensitivityLabel(value: Float) {
        binding.txtCursorSensitivityValue.text =
            getString(R.string.settings_cursor_sensitivity_value_format, value)
    }

    /**
     * *** NOVO - v0.9.6, item 3 ***: mesmo padrão de clique já usado pelos
     * dois toggles do rodapé do drawer (ver `MainActivity.setupDrawer()`) -
     * a linha inteira é clicável, o `MaterialSwitch` em si fica
     * não-clicável/não-focável e só reflete o estado. `ReconnectionManager`
     * é quem realmente lê essa preferência (em `scheduleReconnect()` e
     * `triggerImmediateRetry()`) - esta tela só grava.
     */
    private fun setupAutoReconnect() {
        binding.switchAutoReconnect.isChecked = UserPreferences.isAutoReconnectEnabled(this)
        binding.itemAutoReconnect.setOnClickListener {
            val enabled = !binding.switchAutoReconnect.isChecked
            binding.switchAutoReconnect.isChecked = enabled
            UserPreferences.setAutoReconnectEnabled(this, enabled)
        }
    }

    /**
     * *** NOVO - v0.9.6, item 4 ***: mesmo padrão de clique-na-linha-inteira
     * dos outros dois toggles. O diálogo de confirmação em si (o que
     * aparece de fato antes de desligar a TV) mora em `MainActivity.power()`
     * - esta tela só liga/desliga o comportamento.
     */
    private fun setupPowerOffConfirm() {
        binding.switchPowerOffConfirm.isChecked = UserPreferences.isPowerOffConfirmationEnabled(this)
        binding.itemPowerOffConfirm.setOnClickListener {
            val enabled = !binding.switchPowerOffConfirm.isChecked
            binding.switchPowerOffConfirm.isChecked = enabled
            UserPreferences.setPowerOffConfirmationEnabled(this, enabled)
        }
    }

    /**
     * *** NOVO - v0.9.6, item 5 ***: versão lida via `PackageManager` em
     * vez de `BuildConfig.VERSION_NAME` - o projeto não tinha
     * `buildFeatures.buildConfig = true` habilitado antes desta versão, e
     * não fazia sentido mexer nisso só pra mostrar um número de versão
     * (mesmo raciocínio de não fazer refatoração oportunista fora do
     * escopo desta tela).
     */
    private fun setupAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "?"
        }
        binding.txtAboutVersion.text = getString(R.string.settings_about_version_format, versionName)

        binding.txtAboutGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/brunoCodds/smartremote"))
            startActivity(intent)
        }
    }

    /**
     * *** NOVO - v0.9.6, item 10 ***: só reseta os itens desta tela que já
     * existem ([UserPreferences.resetToDefaults] documenta exatamente
     * quais). Depois de resetar, atualiza os controles na tela na hora -
     * sem isso o slider e os switches ficariam mostrando o valor antigo
     * até a Activity ser recriada.
     */
    private fun setupResetToDefaults() {
        binding.txtResetDefaults.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset_confirm_title)
                .setMessage(R.string.settings_reset_confirm_message)
                .setPositiveButton(R.string.settings_reset_confirm_positive) { _, _ ->
                    UserPreferences.resetToDefaults(this)
                    setupCursorSensitivity()
                    setupAutoReconnect()
                    setupPowerOffConfirm()
                    setupButtonVisibility()
                    setupPreferredApps()
                    Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * *** NOVO - v0.9.6, item 7 ***: mesmo padrão de clique-na-linha-inteira
     * dos outros toggles desta tela. Quem realmente aplica a visibilidade
     * na tela principal é `MainActivity.applyButtonVisibilityPreferences()`
     * (chamado em onCreate/onResume de lá) - esta tela só grava.
     */
    private fun setupButtonVisibility() {
        binding.switchShowAssistant.isChecked = UserPreferences.isAssistantButtonVisible(this)
        binding.itemShowAssistant.setOnClickListener {
            val enabled = !binding.switchShowAssistant.isChecked
            binding.switchShowAssistant.isChecked = enabled
            UserPreferences.setAssistantButtonVisible(this, enabled)
        }

        binding.switchShowAbc.isChecked = UserPreferences.isAbcButtonVisible(this)
        binding.itemShowAbc.setOnClickListener {
            val enabled = !binding.switchShowAbc.isChecked
            binding.switchShowAbc.isChecked = enabled
            UserPreferences.setAbcButtonVisible(this, enabled)
        }
    }

    /**
     * *** NOVO - v0.9.6, item 7 ***: os 3 atalhos configuráveis de
     * `rowStreaming` na tela principal - posição/quantidade continuam
     * fixas (isso é item 9), só QUAL app cada um dispara é escolhido
     * aqui, entre os 10 do [AppCatalog] (mesmo catálogo do
     * `AppsBottomSheet`). `MainActivity.applyPreferredApps()` é quem
     * realmente aplica a escolha nos botões da tela principal.
     */
    private fun setupPreferredApps() {
        binding.txtAppSlot1Label.text = getString(R.string.settings_preferred_app_slot_format, 1)
        binding.txtAppSlot2Label.text = getString(R.string.settings_preferred_app_slot_format, 2)
        binding.txtAppSlot3Label.text = getString(R.string.settings_preferred_app_slot_format, 3)

        refreshAppSlotValue(1, binding.txtAppSlot1Value)
        refreshAppSlotValue(2, binding.txtAppSlot2Value)
        refreshAppSlotValue(3, binding.txtAppSlot3Value)

        binding.itemAppSlot1.setOnClickListener { showAppPickerDialog(1, binding.txtAppSlot1Value) }
        binding.itemAppSlot2.setOnClickListener { showAppPickerDialog(2, binding.txtAppSlot2Value) }
        binding.itemAppSlot3.setOnClickListener { showAppPickerDialog(3, binding.txtAppSlot3Value) }
    }

    private fun refreshAppSlotValue(slot: Int, valueView: android.widget.TextView) {
        val key = UserPreferences.getPreferredAppSlot(this, slot)
        valueView.text = AppCatalog.byKey(key)?.label ?: key.name
    }

    /** Mesmo padrão de `MainActivity.showLanguagePicker()` - lista de escolha única, aplica e fecha no toque, sem precisar de botão "OK" separado. */
    private fun showAppPickerDialog(slot: Int, valueView: android.widget.TextView) {
        val labels = AppCatalog.apps.map { it.label }.toTypedArray()
        val currentKey = UserPreferences.getPreferredAppSlot(this, slot)
        val currentIndex = AppCatalog.apps.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_preferred_apps_dialog_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen: RemoteKey = AppCatalog.apps[which].key
                UserPreferences.setPreferredAppSlot(this, slot, chosen)
                refreshAppSlotValue(slot, valueView)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

