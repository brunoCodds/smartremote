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
 * Fluxo: usuário toca em "Procurar TVs" -> DeviceScanner busca na rede ->
 * lista é exibida -> usuário seleciona uma TV -> toca em "Conectar" -> o
 * dispositivo é salvo via TvManager E o pareamento/conexão é iniciado
 * imediatamente (a TV pode mostrar o popup de autorização) -> só quando a
 * conexão é efetivamente estabelecida esta tela fecha e volta para a
 * MainActivity. Se der erro, a tela permanece aberta para tentar de novo
 * ou escolher outro dispositivo.
 */
class DeviceDiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDiscoveryBinding
    private lateinit var scanner: DeviceScanner
    private lateinit var adapter: DeviceListAdapter

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

    /** Salva a TV escolhida e já inicia o pareamento/conexão em seguida. */
    private fun confirmSelection() {
        val device = selectedDevice ?: return
        TvManager.saveDevice(applicationContext, device)
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
                Toast.makeText(
                    this@DeviceDiscoveryActivity,
                    getString(R.string.device_connected_format, device.name),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }

            override fun onPairingRequired() {
                binding.txtConnectionStatus.text = getString(R.string.connection_status_pairing)
            }

            override fun onError(message: String) {
                setConnectingState(false)
                binding.txtConnectionStatus.text = message
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

    override fun onDestroy() {
        scanner.stopScan()
        if (!connectionEstablished) {
            TvManager.disconnect()
        }
        super.onDestroy()
    }
}
