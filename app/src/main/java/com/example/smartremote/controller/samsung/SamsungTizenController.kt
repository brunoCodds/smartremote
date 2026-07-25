package com.example.smartremote.controller.samsung

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.controller.TvController
import com.example.smartremote.diagnostic.DiagnosticLogType
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.model.TvDevice
import com.example.smartremote.util.Constants
import com.example.smartremote.util.CredentialStore

/**
 * Primeiro TvController real do app: conexão e pareamento com TVs Samsung
 * (Tizen), via WebSocket (porta 8002, protocolo "Samsung Remote Control").
 *
 * Além de conectar/parear/reconectar, implementa [sendRemoteKey] para os
 * comandos de navegação, energia, volume, canal e play/pause - ver
 * [KEY_CODE_MAP] para a lista exata de teclas suportadas nesta fase.
 *
 * Fluxo:
 *  1. Se já existe token salvo (CredentialStore) -> conecta direto com ele.
 *  2. Se não existe -> abre o socket sem token; a TV mostra o popup de
 *     autorização; ao aceitar, a TV manda um evento com o token novo.
 *  3. Token novo é salvo, o socket é fechado e reaberto já autenticado
 *     (reconexão), confirmando que o token funciona de fato.
 *  4. Se o token salvo for rejeitado (TV resetada/desparelhada), descarta
 *     e volta para o fluxo de pareamento (passo 2).
 *
 * Todas as etapas são refletidas no DiagnosticManager, incluindo erros.
 */
class SamsungTizenController(
    private val context: Context,
    private val device: TvDevice
) : TvController {

    private val socketClient = SamsungSocketClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val credentialDeviceId = device.deviceId ?: device.ip

    @Volatile private var connected = false
    private var currentListener: TvConnectionListener? = null
    private var pairingTimeoutRunnable: Runnable? = null

    override fun connect(listener: TvConnectionListener) {
        currentListener = listener
        DiagnosticManager.updateDevice(device)
        DiagnosticManager.setController(CONTROLLER_NAME)
        DiagnosticManager.setLastError(null)

        val savedToken = CredentialStore.get(context, credentialDeviceId, Constants.SAMSUNG_CREDENTIAL_TYPE)
        if (savedToken != null) {
            DiagnosticManager.setToken(savedToken)
            connectWithToken(savedToken)
        } else {
            startPairing()
        }
    }

    override fun disconnect() {
        cancelPairingTimeout()
        socketClient.close()
        connected = false
        DiagnosticManager.setConnectionStatus("Desconectado")
        DiagnosticManager.log("Desconectado", DiagnosticLogType.INFO)
    }

    override fun isConnected(): Boolean = connected

    // ===================== FLUXO DE CONEXÃO/PAREAMENTO =====================

    private fun startPairing() {
        DiagnosticManager.setConnectionStatus("Conectando")
        DiagnosticManager.log("Tentando conectar", DiagnosticLogType.NETWORK)

        val url = SamsungProtocol.buildSocketUrl(device.ip, token = null)
        socketClient.connect(url) { event -> handleSocketEvent(event, hadToken = false) }
    }

    private fun connectWithToken(token: String, isReconnectAfterPairing: Boolean = false) {
        if (!isReconnectAfterPairing) {
            DiagnosticManager.setConnectionStatus("Conectando")
            DiagnosticManager.log("Tentando conectar", DiagnosticLogType.NETWORK)
        }

        val url = SamsungProtocol.buildSocketUrl(device.ip, token)
        socketClient.connect(url) { event -> handleSocketEvent(event, hadToken = true) }
    }

    private fun handleSocketEvent(event: SamsungSocketClient.SocketEvent, hadToken: Boolean) {
        when (event) {
            is SamsungSocketClient.SocketEvent.Open -> onSocketOpen(hadToken)
            is SamsungSocketClient.SocketEvent.Message -> onSocketMessage(event.text, hadToken)
            is SamsungSocketClient.SocketEvent.Failure -> onSocketFailure(event.message)
            is SamsungSocketClient.SocketEvent.Closed -> onSocketClosed()
        }
    }

    private fun onSocketOpen(hadToken: Boolean) {
        DiagnosticManager.log("WebSocket conectado", DiagnosticLogType.NETWORK)
        if (!hadToken) {
            DiagnosticManager.log("Solicitando autorização", DiagnosticLogType.INFO)
            DiagnosticManager.setConnectionStatus("Aguardando confirmação na TV")
            DiagnosticManager.log("Aguardando confirmação na TV", DiagnosticLogType.INFO)
            notifyPairingRequired()
            schedulePairingTimeout()
        }
    }

    private fun onSocketMessage(raw: String, hadToken: Boolean) {
        when (val result = SamsungProtocol.parseEvent(raw)) {
            is SamsungProtocol.SamsungEvent.Connected -> onConnectedEvent(result.token, hadToken)
            is SamsungProtocol.SamsungEvent.Unauthorized -> onUnauthorizedEvent(hadToken)
            is SamsungProtocol.SamsungEvent.Unknown -> {
                DiagnosticManager.setLastResponse(result.eventName ?: raw)
            }
        }
    }

    private fun onConnectedEvent(newToken: String?, hadToken: Boolean) {
        cancelPairingTimeout()

        if (hadToken) {
            // Já estávamos conectando com um token (salvo ou recém-obtido): sessão confirmada.
            markConnected()
            return
        }

        // !hadToken: este é o resultado do pareamento (usuário acabou de aceitar o popup).
        if (newToken == null) {
            // Caso raro: TV autorizou mas não retornou token - segue conectado mesmo assim.
            markConnected()
            return
        }

        DiagnosticManager.log("Token recebido", DiagnosticLogType.INFO)
        DiagnosticManager.setToken(newToken)
        CredentialStore.save(context, credentialDeviceId, Constants.SAMSUNG_CREDENTIAL_TYPE, newToken)
        DiagnosticManager.log("Token salvo", DiagnosticLogType.INFO)

        socketClient.close()
        DiagnosticManager.setConnectionStatus("Reconectando")
        DiagnosticManager.log("Reconectando", DiagnosticLogType.NETWORK)
        connectWithToken(newToken, isReconnectAfterPairing = true)
    }

    private fun onUnauthorizedEvent(hadToken: Boolean) {
        socketClient.close()
        if (hadToken) {
            // Token salvo não é mais válido (ex: TV resetada/desparelhada) - descarta e reinicia o pareamento.
            DiagnosticManager.log("Erro de autenticação", DiagnosticLogType.ERROR)
            DiagnosticManager.setLastError("Token inválido - iniciando novo pareamento")
            DiagnosticManager.setToken(null)
            CredentialStore.clear(context, credentialDeviceId, Constants.SAMSUNG_CREDENTIAL_TYPE)
            startPairing()
        } else {
            connected = false
            DiagnosticManager.setConnectionStatus("Desconectado")
            DiagnosticManager.log("Erro de autenticação", DiagnosticLogType.ERROR)
            DiagnosticManager.setLastError("A TV recusou a conexão")
            notifyError("A TV recusou a conexão")
        }
    }

    private fun onSocketFailure(message: String) {
        cancelPairingTimeout()
        connected = false
        DiagnosticManager.setConnectionStatus("Desconectado")
        DiagnosticManager.log("Erro de conexão: $message", DiagnosticLogType.ERROR)
        DiagnosticManager.setLastError(message)
        notifyError("Erro de conexão: $message")
    }

    private fun onSocketClosed() {
        if (connected) {
            connected = false
            DiagnosticManager.setConnectionStatus("Desconectado")
            DiagnosticManager.log("Conexão encerrada", DiagnosticLogType.NETWORK)
        }
    }

    private fun markConnected() {
        connected = true
        DiagnosticManager.setConnectionStatus("Conectado")
        DiagnosticManager.setLastError(null)
        DiagnosticManager.log("Conectado", DiagnosticLogType.INFO)
        notifyConnected()
    }

    // ===================== TIMEOUT DO PAREAMENTO =====================

    private fun schedulePairingTimeout() {
        cancelPairingTimeout()
        val runnable = Runnable {
            connected = false
            DiagnosticManager.setConnectionStatus("Desconectado")
            DiagnosticManager.log("Timeout", DiagnosticLogType.ERROR)
            DiagnosticManager.setLastError("Tempo esgotado aguardando confirmação na TV")
            socketClient.close()
            notifyError("Tempo esgotado aguardando confirmação na TV")
        }
        pairingTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, Constants.SAMSUNG_PAIRING_TIMEOUT_MS)
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
    }

    // ===================== ENTREGA DO CALLBACK NO MAIN THREAD =====================
    // Os eventos do WebSocket chegam em threads de background do OkHttp;
    // por convenção do projeto (mesmo padrão de DeviceScanner/MdnsScanner),
    // quem consome TvConnectionListener não deve se preocupar com threading.

    private fun notifyConnected() = mainHandler.post { currentListener?.onConnected() }
    private fun notifyPairingRequired() = mainHandler.post { currentListener?.onPairingRequired() }
    private fun notifyError(message: String) = mainHandler.post { currentListener?.onError(message) }

    // ===================== COMANDOS =====================

    override fun sendRemoteKey(key: RemoteKey) {
        if (!connected) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }

        val keyCode = KEY_CODE_MAP[key]
        if (keyCode == null) {
            DiagnosticManager.log("Comando ainda não suportado: $key", DiagnosticLogType.WARNING)
            return
        }

        DiagnosticManager.setLastCommand(key.name)
        val message = SamsungProtocol.buildRemoteControlCommand(keyCode)
        val sent = socketClient.send(message)

        if (!sent) {
            // O socket estava marcado como conectado, mas o envio falhou
            // (ex: TV caiu no meio da leitura de estado) - mesma mensagem
            // do caso "desconectado", sem lançar exceção.
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }

        DiagnosticManager.log("Comando enviado: ${key.name} ($keyCode)", DiagnosticLogType.NETWORK)
        // A resposta (se a TV enviar alguma) chega em onSocketMessage() e já
        // é refletida via DiagnosticManager.setLastResponse() - não precisa
        // de tratamento específico por comando aqui.
    }

    private companion object {
        const val CONTROLLER_NAME = "SamsungTizenController"

        /**
         * Tradução RemoteKey -> código oficial do protocolo Samsung Remote
         * Control. Só entram aqui as teclas já validadas nesta fase - as
         * demais (KEYBOARD, ASSISTANT, NETFLIX, PRIME_VIDEO, GLOBOPLAY)
         * usam mecanismos diferentes do protocolo (SendInputString,
         * lançamento de app via ms.channel.emit com app ID específico) e
         * ficam para uma fase futura dedicada a elas - até lá, sendRemoteKey
         * simplesmente registra "não suportado" para essas teclas, sem
         * quebrar nada.
         */
        val KEY_CODE_MAP: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "KEY_UP",
            RemoteKey.DOWN to "KEY_DOWN",
            RemoteKey.LEFT to "KEY_LEFT",
            RemoteKey.RIGHT to "KEY_RIGHT",
            RemoteKey.OK to "KEY_ENTER",
            RemoteKey.BACK to "KEY_RETURN",
            RemoteKey.HOME to "KEY_HOME",
            RemoteKey.POWER to "KEY_POWER",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",
            RemoteKey.PLAY_PAUSE to "KEY_PAUSE"
        )
    }
}
