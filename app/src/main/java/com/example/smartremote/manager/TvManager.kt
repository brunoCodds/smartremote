package com.example.smartremote.manager

import android.content.Context
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.controller.TvController
import com.example.smartremote.controller.samsung.SamsungTizenController
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.DeviceStorage

/**
 * Ponto único de acesso da UI à TV configurada e à conexão ativa com ela.
 *
 * A partir desta fase é um singleton (object) - e não mais uma classe
 * instanciada por Activity - porque agora ele guarda o TvController e o
 * estado de conexão realmente ativos. Se continuasse sendo uma instância
 * por Activity, a conexão estabelecida na DeviceDiscoveryActivity se
 * perderia ao voltar para a MainActivity. Segue o mesmo padrão já usado
 * por DiagnosticManager e DeviceStorage: métodos recebem Context quando
 * precisam, sem guardar Context como campo de instância.
 *
 * Decide qual TvController usar com base em `device.os` (hoje só Tizen -
 * Samsung; os demais fabricantes entram aqui conforme forem
 * implementados) e delega a ele a conexão de fato, mantendo o
 * ConnectionManager como registro simples de estado.
 */
object TvManager {

    private val connectionManager = ConnectionManager()
    private var currentController: TvController? = null

    fun saveDevice(context: Context, device: TvDevice) {
        DeviceStorage.save(context, device)
    }

    fun getSavedDevice(context: Context): TvDevice? =
        DeviceStorage.load(context)

    fun hasSavedDevice(context: Context): Boolean =
        getSavedDevice(context) != null

    fun clearSavedDevice(context: Context) {
        DeviceStorage.clear(context)
    }

    /**
     * Inicia a conexão/pareamento com [device], escolhendo o TvController
     * correto conforme o sistema operacional detectado na descoberta.
     * Qualquer conexão anterior é encerrada antes de iniciar uma nova, e o
     * DiagnosticManager é limpo para não misturar logs de dispositivos
     * diferentes.
     */
    fun connect(context: Context, device: TvDevice, listener: TvConnectionListener) {
        currentController?.disconnect()
        DiagnosticManager.clear()

        val controller = createControllerFor(context, device)
        if (controller == null) {
            listener.onError("TV ainda não suportada nesta versão")
            return
        }

        currentController = controller
        connectionManager.connect(device)

        controller.connect(object : TvConnectionListener {
            override fun onConnected() {
                connectionManager.markConnected()
                listener.onConnected()
            }

            override fun onPairingRequired() {
                listener.onPairingRequired()
            }

            override fun onError(message: String) {
                connectionManager.disconnect()
                listener.onError(message)
            }
        })
    }

    /** Encerra a conexão ativa, se existir. Seguro chamar mesmo sem conexão. */
    fun disconnect() {
        currentController?.disconnect()
        currentController = null
        connectionManager.disconnect()
    }

    fun isConnected(): Boolean = connectionManager.isConnected()

    private fun createControllerFor(context: Context, device: TvDevice): TvController? =
        when (device.os) {
            TvOperatingSystem.TIZEN -> SamsungTizenController(context.applicationContext, device)
            else -> null
        }
}
