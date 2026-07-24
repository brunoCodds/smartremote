package com.example.smartremote.discovery

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartremote.R
import com.example.smartremote.databinding.ActivityDeviceDiscoveryBinding
import com.example.smartremote.manager.TvManager
import com.example.smartremote.model.TvDevice

/**
 * Tela de descoberta de Smart TVs na rede local (SSDP + mDNS).
 *
 * Fluxo: usuário toca em "Procurar TVs" -> DeviceScanner busca na rede ->
 * lista é exibida -> usuário seleciona uma TV -> toca em "Conectar" -> o
 * dispositivo é salvo via TvManager -> volta para a MainActivity.
 *
 * Nenhum comando é enviado à TV nesta fase - apenas descoberta e seleção.
 */
class DeviceDiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDiscoveryBinding
    private lateinit var scanner: DeviceScanner
    private lateinit var adapter: DeviceListAdapter
    private lateinit var tvManager: TvManager

    private var selectedDevice: TvDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = DeviceScanner(applicationContext)
        tvManager = TvManager(applicationContext)

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

    private fun confirmSelection() {
        val device = selectedDevice ?: return
        tvManager.saveDevice(device)
        Toast.makeText(
            this, getString(R.string.device_saved_format, device.name), Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onDestroy() {
        scanner.stopScan()
        super.onDestroy()
    }
}
