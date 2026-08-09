package com.example.smartremote.discovery

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartremote.R
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.databinding.ActivityDeviceDiscoveryBinding
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.TvDevice

/**
 * Tela de descoberta de Smart TVs na rede local (SSDP + mDNS).
 *
 * Fluxo de pareamento: usuário toca em "Procurar TVs" -> DeviceScanner busca
 * na rede -> lista é exibida (com selo "Pareada" nas que já forem
 * conhecidas, mas SEM nunca esconder nenhuma) -> usuário seleciona uma TV
 * -> toca em "Conectar" -> o app tenta conectar imediatamente (a TV pode
 * mostrar o popup de autorização) -> só quando a conexão é EFETIVAMENTE
 * estabelecida (onConnected) é que o dispositivo é salvo como pareado, e
 * esta tela fecha e volta para a MainActivity. Se o usuário negar o popup,
 * der timeout ou qualquer erro, nada é salvo e a tela permanece aberta
 * para tentar de novo ou escolher outro dispositivo.
 *
 * Fluxo de TVs já pareadas: a seção "TVs pareadas" lista TODAS as TVs
 * pareadas (múltiplas, ver TvManager/DeviceStorage). Tocar em uma revela
 * suas ações (Esquecer / Conectar-Desconectar). Esquecer uma TV nunca afeta
 * as demais.
 *
 * *** CORREÇÃO - onDestroy() derrubava conexões alheias a esta tela ***
 * Antes, [connectionEstablished] só virava true quando uma conexão
 * INICIADA AQUI completava com sucesso. Isso significa que, se o usuário
 * já estava conectado a uma TV (ex: reconexão automática feita pela
 * MainActivity ao abrir o app) e apenas abria esta tela para olhar/fechar
 * sem tocar em nada, o onDestroy() encontrava connectionEstablished=false
 * e chamava TvManager.disconnect() - derrubando uma conexão que não tinha
 * nada a ver com esta tela. [attemptedNewConnection] agora distingue "uma
 * conexão nova foi tentada aqui" de "não fiz nada aqui", então só
 * desconecta quando uma tentativa de conexão realmente começou nesta tela
 * e não terminou em sucesso (ex: usuário trocou de TV mas cancelou/deu
 * erro no meio do caminho).
 */
class DeviceDiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDiscoveryBinding
    private lateinit var scanner: DeviceScanner
    private lateinit var adapter: DeviceListAdapter
    private lateinit var pairedAdapter: PairedDeviceListAdapter

    private var selectedDevice: TvDevice? = null

    /**
     * Marca que uma tentativa de conexão (nova TV ou reconexão de uma TV
     * já pareada) foi INICIADA a partir desta tela - ver [startConnection].
     * Usada em conjunto com [connectionEstablished] para decidir, em
     * [onDestroy], se é seguro desconectar.
     */
    private var attemptedNewConnection = false

    /** Evita que onDestroy() derrube uma conexão que acabou de ser estabelecida com sucesso. */
    private var connectionEstablished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = DeviceScanner(applicationContext)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupPairedDevicesSection()
        refreshPairedDevicesSection()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = DeviceListAdapter { device ->
            selectedDevice = device
            binding.btnConnect.isEnabled = true
        }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnRefresh.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { confirmSelection() }
        binding.btnConnect.isEnabled = false
    }

    private fun startScan() {
        adapter.clear()
        selectedDevice = null
        binding.btnConnect.isEnabled = false
        binding.emptyStateText.visibility = View.GONE
        binding.txtConnectionStatus.visibility = View.GONE
        setScanningState(true)

        scanner.startScan(object : DeviceScanner.Listener {
            override fun onDeviceFound(device: TvDevice) {
                adapter.addDevice(device)
            }

            override fun onDeviceUpgraded(previousKey: String, device: TvDevice) {
                // Uma entrada genérica já exibida (ex: "Dispositivo SSDP"
                // sem marca, comum em TVs Samsung com serviço Ginga/SBTVD)
                // acabou de ser identificada de verdade - troca o item já
                // exibido em vez de adicionar uma linha duplicada. Ver
                // DeviceScanner.Listener.onDeviceUpgraded.
                adapter.replaceDevice(previousKey, device)
            }

            override fun onScanFinished(devices: List<TvDevice>) {
                setScanningState(false)
                binding.emptyStateText.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
                refreshPairedBadges()
            }

            override fun onScanError(message: String) {
                setScanningState(false)
                Toast.makeText(this@DeviceDiscoveryActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setScanningState(scanning: Boolean) {
        binding.progressIndicator.visibility = if (scanning) View.VISIBLE else View.GONE
        binding.btnScan.isEnabled = !scanning
        binding.btnRefresh.isEnabled = !scanning
    }

    /** Tenta conectar/parear com a TV escolhida na descoberta. Só é salva como pareada se a conexão for confirmada (ver startConnection/onConnected). */
    private fun confirmSelection() {
        val device = selectedDevice ?: return
        startConnection(device)
    }

    private fun startConnection(device: TvDevice) {
        attemptedNewConnection = true
        setConnectingState(true)
        binding.txtConnectionStatus.visibility = View.VISIBLE
        binding.txtConnectionStatus.text = getString(R.string.connection_status_connecting)

        TvManager.connect(applicationContext, device, object : TvConnectionListener {
            override fun onConnected() {
                connectionEstablished = true
                setConnectingState(false)

                // Só agora - com a conexão de fato confirmada pela TV - a
                // TV é salva como pareada. Evita registrar uma TV "pareada"
                // que na verdade teve a autorização negada ou deu timeout,
                // o que deixaria uma entrada órfã (sem credencial válida)
                // na lista de TVs pareadas.
                TvManager.pairDevice(applicationContext, device)

                Toast.makeText(
                    this@DeviceDiscoveryActivity,
                    getString(R.string.device_connected_format, device.name),
                    Toast.LENGTH_SHORT
                ).show()
                refreshPairedDevicesSection()
                refreshPairedBadges()
                finish()
            }

            override fun onPairingRequired() {
                binding.txtConnectionStatus.text = getString(R.string.connection_status_pairing)
            }

            override fun onPairingCodeRequired() {
                // *** v0.9, item 3 (Android TV) ***: diferente de
                // onPairingRequired() (Samsung/LG - só um popup na TV
                // pra aceitar), aqui o usuário precisa DIGITAR um código
                // que a TV está exibindo - ver KDoc completo em
                // AndroidTvRemoteProtocol.computePairingSecret.
                binding.txtConnectionStatus.text = getString(R.string.connection_status_waiting_pairing_code)
                showAndroidTvPairingCodeDialog()
            }

            override fun onError(message: String) {
                setConnectingState(false)
                binding.txtConnectionStatus.text = message
                refreshPairedDevicesSection()
            }
        })
    }

    /**
     * *** NOVO - v0.9, item 3 (Android TV) ***
     *
     * Diálogo simples (não uma tela nova nem um BottomSheet - deliberado,
     * para não ampliar o escopo desta versão além do estritamente
     * necessário para o pareamento funcionar) que pede o código de 6
     * dígitos exibido na TV e repassa via [TvManager.submitPairingCode].
     * Fechar o diálogo (Cancelar/tocar fora) não desfaz a tentativa de
     * conexão em andamento - só cancela a DIGITAÇÃO; se o usuário mudar
     * de ideia de verdade, ele usa o botão de conectar/cancelar normal da
     * tela (a conexão de pareamento é encerrada nesse fluxo já existente,
     * não por este diálogo).
     */
    private fun showAndroidTvPairingCodeDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.androidtv_pairing_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.androidtv_pairing_code_dialog_title)
            .setMessage(R.string.androidtv_pairing_code_dialog_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.androidtv_pairing_code_confirm) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.length != 6) {
                    Toast.makeText(this, R.string.androidtv_pairing_code_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                TvManager.submitPairingCode(code)
            }
            .setNegativeButton(R.string.androidtv_pairing_code_cancel, null)
            .show()
    }

    /** Bloqueia novas buscas/seleções enquanto a conexão está em andamento. */
    private fun setConnectingState(connecting: Boolean) {
        binding.progressIndicator.visibility = if (connecting) View.VISIBLE else View.GONE
        binding.btnScan.isEnabled = !connecting
        binding.btnRefresh.isEnabled = !connecting
        binding.btnConnect.isEnabled = !connecting && selectedDevice != null
    }

    // ===================== SEÇÃO "TVS PAREADAS" (múltiplas) =====================
    // Lista TODAS as TVs pareadas (ver DeviceStorage/TvManager), cada uma
    // com suas próprias ações. Esquecer uma TV nunca apaga as outras.

    private fun setupPairedDevicesSection() {
        pairedAdapter = PairedDeviceListAdapter(
            onConnect = { device -> startConnection(device) },
            onDisconnect = {
                TvManager.disconnect()
                connectionEstablished = false
                refreshPairedDevicesSection()
            },
            onForget = { device ->
                TvManager.forgetDevice(applicationContext, device.stableKey())
                refreshPairedDevicesSection()
                refreshPairedBadges()
            }
        )
        binding.recyclerPairedDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerPairedDevices.adapter = pairedAdapter
    }

    /** Recarrega a seção a partir do que está pareado em TvManager. Oculta tudo se não houver nenhuma TV pareada. */
    private fun refreshPairedDevicesSection() {
        val devices = TvManager.getPairedDevices(applicationContext)
        binding.sectionPairedDevices.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        pairedAdapter.submitList(devices)
        pairedAdapter.updateConnectedKey(TvManager.getConnectedDeviceKey())
    }

    /** Atualiza só os selos "Pareada" da lista de descoberta, sem recarregar a lista de pareadas inteira. */
    private fun refreshPairedBadges() {
        val pairedKeys = TvManager.getPairedDevices(applicationContext).map { it.stableKey() }.toSet()
        adapter.updatePairedKeys(pairedKeys)
    }

    /**
     * Só derruba a conexão ativa se uma tentativa de conexão foi de fato
     * iniciada NESTA tela (ver [startConnection]) e não terminou em
     * sucesso (usuário cancelou saindo no meio do pareamento, erro,
     * timeout, etc). Se o usuário só abriu e fechou esta tela sem tentar
     * conectar nada, uma conexão pré-existente (ex: reconectada
     * automaticamente pela MainActivity) permanece intacta.
     */
    override fun onDestroy() {
        scanner.stopScan()
        if (attemptedNewConnection && !connectionEstablished) {
            TvManager.disconnect()
        }
        super.onDestroy()
    }
}
