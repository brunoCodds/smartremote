package com.example.smartremote.manager

import android.content.Context
import android.util.Log
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.controller.TvController
import com.example.smartremote.controller.samsung.SamsungTizenController
import com.example.smartremote.diagnostic.DiagnosticLogType
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.Constants
import com.example.smartremote.util.CredentialStore
import com.example.smartremote.util.DeviceStorage

/**
 * Ponto único de acesso da UI às TVs pareadas e à conexão ativa.
 *
 * A partir desta fase suporta MÚLTIPLAS TVs pareadas simultaneamente
 * ([getPairedDevices]), mas continua controlando apenas UMA conexão ativa
 * por vez ([ConnectionManager]) - faz sentido para um controle remoto: o
 * usuário sempre está "apontando" para uma TV de cada vez, mesmo tendo
 * várias cadastradas.
 *
 * Continua sendo um singleton (object) pelo mesmo motivo de antes: guarda o
 * TvController e o estado de conexão realmente ativos, que não podem se
 * perder ao trocar de Activity.
 *
 * Cada TV é identificada por [TvDevice.stableKey] (deviceId/USN quando
 * disponível, com fallback isolado em TvDevice) - nunca mais por IP puro.
 * Isso permite que uma TV pareada mude de IP (renovação de DHCP) sem que o
 * app a trate como um dispositivo novo ou perca o token salvo.
 *
 * Decide qual TvController usar com base em `device.os` (hoje só Tizen -
 * Samsung; os demais fabricantes entram aqui conforme forem
 * implementados) e delega a ele a conexão de fato, mantendo o
 * ConnectionManager como registro simples de estado.
 */
object TvManager {

    // TAG usada apenas pelos logs temporários de diagnóstico desta etapa
    // (investigação de "TV ainda não suportada").
    private const val DIAG_TAG = "SsdpDiagnostic"

    private val connectionManager = ConnectionManager()
    private var currentController: TvController? = null

    // ===================== API NOVA (plural - múltiplas TVs) =====================

    /** Pareia (salva) [device], ou atualiza os dados de uma TV já pareada com a mesma chave estável. */
    fun pairDevice(context: Context, device: TvDevice) {
        DeviceStorage.saveOrUpdate(context, device)
    }

    /** Todas as TVs pareadas no momento. Lista vazia se nenhuma. */
    fun getPairedDevices(context: Context): List<TvDevice> =
        DeviceStorage.getAll(context)

    /** Se existe alguma TV pareada com esta chave estável. */
    fun isPaired(context: Context, key: String): Boolean =
        DeviceStorage.getByKey(context, key) != null

    /**
     * Esquece a TV de chave [key]: remove o pareamento e a credencial
     * associada (quando o fabricante da TV tiver um tipo de credencial
     * conhecido - ver [credentialTypeFor]). Não afeta nenhuma outra TV
     * pareada. Se a TV esquecida for a que está conectada no momento, a
     * conexão ativa também é encerrada.
     */
    fun forgetDevice(context: Context, key: String) {
        val device = DeviceStorage.getByKey(context, key)

        if (connectionManager.isConnectedTo(key)) {
            disconnect()
        }

        DeviceStorage.remove(context, key)

        device?.let {
            val credentialType = credentialTypeFor(it.os)
            if (credentialType != null) {
                val credentialDeviceId = it.deviceId ?: it.ip
                CredentialStore.clear(context, credentialDeviceId, credentialType)
            }
        }

        Log.d(DIAG_TAG, "TvManager.forgetDevice() removeu key=$key")
    }

    // ===================== API ANTIGA (singular) - mantida por compatibilidade =====================
    // Por baixo já delegam para a lista nova. Preferir a API plural acima
    // em código novo.

    /** @deprecated usar [pairDevice]. Mantido apenas por compatibilidade. */
    fun saveDevice(context: Context, device: TvDevice) = pairDevice(context, device)

    /** @deprecated usar [getPairedDevices]. Mantido apenas por compatibilidade; retorna a primeira TV pareada. */
    fun getSavedDevice(context: Context): TvDevice? = getPairedDevices(context).firstOrNull()

    /** @deprecated usar [getPairedDevices].isNotEmpty(). Mantido apenas por compatibilidade. */
    fun hasSavedDevice(context: Context): Boolean = getPairedDevices(context).isNotEmpty()

    /** @deprecated usar [forgetDevice]. Mantido apenas por compatibilidade; remove TODAS as TVs pareadas. */
    fun clearSavedDevice(context: Context) {
        disconnect()
        DeviceStorage.clearAll(context)
    }

    // ===================== CONEXÃO =====================

    /**
     * Inicia a conexão/pareamento com [device], escolhendo o TvController
     * correto conforme o sistema operacional detectado na descoberta.
     * Qualquer conexão anterior é encerrada antes de iniciar uma nova, e o
     * DiagnosticManager é limpo para não misturar logs de dispositivos
     * diferentes.
     */
    fun connect(context: Context, device: TvDevice, listener: TvConnectionListener) {
        Log.d(DIAG_TAG, "TvManager.connect() recebeu device: $device (key=${device.stableKey()})")
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

    /** Encerra a conexão ativa, se existir. Seguro chamar mesmo sem conexão. Não afeta o pareamento. */
    fun disconnect() {
        currentController?.disconnect()
        currentController = null
        connectionManager.disconnect()
    }

    fun isConnected(): Boolean = connectionManager.isConnected()

    /**
     * Envia um comando genérico (ver [RemoteKey]) para a TV atualmente
     * conectada. Não sabe nada sobre o protocolo do fabricante - só
     * delega ao [TvController] ativo, que decide como (ou se) traduz essa
     * tecla. Se não houver nenhuma TV conectada no momento, registra o
     * mesmo aviso genérico que o controller usaria para o caso de
     * desconexão - aqui de forma independente de fabricante.
     */
    fun sendRemoteKey(key: RemoteKey) {
        val controller = currentController
        if (controller == null) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        controller.sendRemoteKey(key)
    }

    /**
     * Envia um texto livre (teclado digitado ou voz reconhecida) para a
     * TV atualmente conectada. Mesmo padrão de [sendRemoteKey]: não sabe
     * nada sobre o protocolo do fabricante, só delega.
     */
    fun sendText(text: String) {
        val controller = currentController
        if (controller == null) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        controller.sendText(text)
    }

    /** Se a TV de chave [key] é a que está conectada (ou conectando) no momento. */
    fun isConnectedTo(key: String): Boolean = connectionManager.isConnectedTo(key)

    /**
     * Apps que a TV/fabricante atualmente conectado sabe abrir (ver
     * [TvController.supportedApps]). Vazio se não houver TV conectada.
     * Só delega ao controller ativo - TvManager continua sem saber nada
     * de fabricante.
     */
    fun getSupportedApps(): Set<RemoteKey> = currentController?.supportedApps() ?: emptySet()

    /** Chave (stableKey) da TV atualmente conectada, ou null se nenhuma conexão ativa. */
    fun getConnectedDeviceKey(): String? {
        val device = connectionManager.getCurrentDevice() ?: return null
        return device.stableKey().takeIf { connectionManager.isConnected() }
    }

    private fun createControllerFor(context: Context, device: TvDevice): TvController? {
        val controller = when (device.os) {
            TvOperatingSystem.TIZEN -> SamsungTizenController(context.applicationContext, device)
            else -> null
        }
        Log.d(
            DIAG_TAG,
            "TvManager.createControllerFor() -> os=${device.os}, controller=${controller?.let { it::class.simpleName } ?: "NULO (não suportado)"}"
        )
        return controller
    }

    /**
     * Tipo de credencial ([CredentialStore]) usado por cada fabricante,
     * para saber o que limpar em [forgetDevice]. Único lugar que precisa
     * ganhar uma linha nova quando um fabricante novo passar a salvar
     * credencial (LG client-key, certificado Android TV, etc.) - o
     * CredentialStore em si não precisa mudar.
     */
    private fun credentialTypeFor(os: TvOperatingSystem): String? = when (os) {
        TvOperatingSystem.TIZEN -> Constants.SAMSUNG_CREDENTIAL_TYPE
        else -> null
    }
}
