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

        val appIds = APP_LAUNCH_MAP[key]
        if (appIds != null) {
            sendAppLaunch(key, appIds)
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

    /**
     * Manda o comando de abrir app para CADA App ID candidato de [key], em
     * sequência. Isso não é redundância por engano: como a Samsung não
     * publica uma lista oficial de App IDs (ver comentário em
     * SamsungProtocol sobre a fonte comunitária desses valores) e o
     * protocolo é fire-and-forget (a TV não confirma qual ID foi
     * reconhecido, e simplesmente ignora um ID que não exista nela), mandar
     * todos os candidatos é seguro e aumenta a chance real de abrir o app
     * certo em modelos/firmwares diferentes.
     */
    private fun sendAppLaunch(key: RemoteKey, appIds: List<String>) {
        DiagnosticManager.setLastCommand("Abrir app: ${key.name}")

        var anySent = false
        for (appId in appIds) {
            val message = SamsungProtocol.buildAppLaunchCommand(appId)
            if (socketClient.send(message)) {
                anySent = true
            }
        }

        if (!anySent) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }

        DiagnosticManager.log(
            "Aplicativo aberto: ${key.name} (${appIds.size} ID(s) candidato(s) enviado(s))",
            DiagnosticLogType.NETWORK
        )
    }

    /**
     * Envia um texto livre para a TV usando o mecanismo oficial
     * SendInputString (não simula tecla por tecla). Usado tanto pelo
     * teclado digitado quanto pela entrada por voz (ambos convergem aqui
     * em MainActivity).
     *
     * Não é logado o texto em si (mesmo padrão de privacidade já usado
     * para o token - ver DiagnosticManager.setToken), só o tamanho, para
     * não deixar o conteúdo digitado/falado exposto no painel de
     * diagnóstico.
     */
    override fun sendText(text: String) {
        if (!connected) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        if (text.isBlank()) return

        DiagnosticManager.setLastCommand("Texto (${text.length} caractere(s))")
        val message = SamsungProtocol.buildSendTextCommand(text)
        val sent = socketClient.send(message)

        if (!sent) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }

        // Limitação conhecida do protocolo: não há confirmação de que
        // havia um campo de texto focado na TV - só registramos o envio.
        DiagnosticManager.log("Texto enviado (${text.length} caractere(s))", DiagnosticLogType.NETWORK)
    }

    /** Ver KDoc de [TvController.supportedApps]. */
    override fun supportedApps(): Set<RemoteKey> = APP_LAUNCH_MAP.keys

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
            RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",

            // Toggle único do botão físico play/pause. "KEY_PLAY_PAUSE"
            // (valor usado anteriormente aqui) NÃO existe no protocolo
            // Samsung Remote Control - não consta em nenhuma lista de
            // DataOfCmd conhecida (nem na referência comunitária citada em
            // SamsungProtocol, nem em levantamentos mais extensos como o
            // Key_codes.md do samsungctl/ha-samsungtv-tizen). Por ser um
            // protocolo fire-and-forget, a TV apenas ignorava esse código em
            // silêncio - o app registrava "comando enviado" e nada
            // acontecia na tela. "KEY_PAUSE" é o código real mais próximo
            // do botão físico único de play/pause (mesma conclusão a que
            // integrações da comunidade chegaram por tentativa e erro, já
            // que o protocolo só expõe PLAY e PAUSE discretos, sem um
            // toggle nativo).
            RemoteKey.PLAY_PAUSE to "KEY_PAUSE",
            RemoteKey.PLAY to "KEY_PLAY",
            RemoteKey.PAUSE to "KEY_PAUSE",
            RemoteKey.STOP to "KEY_STOP",

            // Teclado numérico
            RemoteKey.NUM_0 to "KEY_0",
            RemoteKey.NUM_1 to "KEY_1",
            RemoteKey.NUM_2 to "KEY_2",
            RemoteKey.NUM_3 to "KEY_3",
            RemoteKey.NUM_4 to "KEY_4",
            RemoteKey.NUM_5 to "KEY_5",
            RemoteKey.NUM_6 to "KEY_6",
            RemoteKey.NUM_7 to "KEY_7",
            RemoteKey.NUM_8 to "KEY_8",
            RemoteKey.NUM_9 to "KEY_9",

            // Teclas coloridas
            RemoteKey.RED to "KEY_RED",
            RemoteKey.GREEN to "KEY_GREEN",
            RemoteKey.YELLOW to "KEY_YELLOW",
            RemoteKey.BLUE to "KEY_BLUE"
        )

        /**
         * Lançamento de apps (mecanismo diferente de KEY_CODE_MAP - ver
         * SamsungProtocol.buildAppLaunchCommand). Cada entrada é uma LISTA
         * de App IDs candidatos (ver comentário em SamsungProtocol sobre a
         * fonte comunitária e a falta de garantia desses valores) -
         * sendAppLaunch manda todos em sequência.
         *
         * RemoteKey.CRUNCHYROLL propositalmente SEM entrada aqui: nenhuma
         * fonte encontrada documenta um App ID confiável para ele nesta
         * fase (ver SamsungProtocol). supportedApps() abaixo reflete isso
         * automaticamente - a UI já sabe desabilitá-lo sem precisar de
         * nenhuma lógica extra.
         */
        val APP_LAUNCH_MAP: Map<RemoteKey, List<String>> = mapOf(
            RemoteKey.NETFLIX to SamsungProtocol.NETFLIX_APP_IDS,
            RemoteKey.PRIME_VIDEO to SamsungProtocol.PRIME_VIDEO_APP_IDS,
            RemoteKey.GLOBOPLAY to SamsungProtocol.GLOBOPLAY_APP_IDS,
            RemoteKey.YOUTUBE to SamsungProtocol.YOUTUBE_APP_IDS,
            RemoteKey.DISNEY_PLUS to SamsungProtocol.DISNEY_PLUS_APP_IDS,
            RemoteKey.MAX to SamsungProtocol.MAX_APP_IDS,
            RemoteKey.APPLE_TV_PLUS to SamsungProtocol.APPLE_TV_PLUS_APP_IDS,
            RemoteKey.PARAMOUNT_PLUS to SamsungProtocol.PARAMOUNT_PLUS_APP_IDS,
            RemoteKey.PLEX to SamsungProtocol.PLEX_APP_IDS
        )
    }
}
