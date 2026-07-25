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
 */
class DeviceDiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDiscoveryBinding
    private lateinit var scanner: DeviceScanner
    private lateinit var adapter: DeviceListAdapter
    private lateinit var pairedAdapter: PairedDeviceListAdapter

    private var selectedDevice: TvDevice? = null

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

            override fun onError(message: String) {
                setConnectingState(false)
                binding.txtConnectionStatus.text = message
                refreshPairedDevicesSection()
            }
        })
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

    override fun onDestroy() {
        scanner.stopScan()
        if (!connectionEstablished) {
            TvManager.disconnect()
        }
        super.onDestroy()
    }
}
