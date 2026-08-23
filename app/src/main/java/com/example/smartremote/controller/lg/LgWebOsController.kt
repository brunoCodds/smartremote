package com.example.smartremote.controller.lg

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartremote.R
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.controller.TvController
import com.example.smartremote.diagnostic.DiagnosticLogType
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.model.TvDevice
import com.example.smartremote.util.Constants
import com.example.smartremote.util.CredentialStore
import org.json.JSONObject

/**
 * TvController para TVs LG rodando webOS, via protocolo SSAP (WebSocket).
 *
 * *** VERSÃO ETAPA 1 (correção crítica do register + diagnóstico) ***
 * O bug que impedia o popup de autorização de aparecer estava no manifest
 * enviado em [LgWebOsProtocol.buildRegisterMessage]: faltava o bloco
 * "signatures" (assinatura RSA fixa, exigida pelo firmware webOS) e o
 * conteúdo de "signed" tinha sido customizado de um jeito incompatível com
 * essa assinatura. Ver documentação detalhada no topo de
 * [LgWebOsProtocol].
 *
 * *** CORREÇÃO 2 (thread) ***
 * Com o register corrigido, o pareamento passou a completar de fato, mas
 * o app crashava logo depois de "registered" com
 * CalledFromWrongThreadException ("Only the original thread that created
 * a view hierarchy can touch its views" - a própria mensagem identifica a
 * thread de origem como "OkHttp https://ip:3001/...", o nome que o OkHttp
 * dá à thread interna de leitura de cada WebSocket).
 *
 * Causa: [LgWebOsSocketClient] entrega os eventos (Open/Message/Closed/
 * Failure) diretamente na thread de callback do OkHttp - nunca na main
 * thread. O fluxo handleMainSocketMessage -> listener?.onConnected() ->
 * TvManager -> a Activity de descoberta (que toca Views e mostra Toast em
 * onConnected()) rodava então inteiro fora da main thread. Diferente de
 * DeviceScanner/MdnsScanner/DiagnosticManager, que já entregam callbacks
 * sempre via Handler(Looper.getMainLooper()), este controller não fazia
 * esse salto - por isso o [mainHandler] abaixo, seguindo o mesmo padrão
 * já usado no resto do app.
 *
 * Diferente da Samsung, o webOS usa DOIS sockets:
 *  - [mainSocket]: conexão principal (porta 3001, wss) - usada para
 *    registro/pareamento e requisições SSAP "de sistema" (poder, volume,
 *    apps - a maioria ainda fora de escopo nesta fase).
 *  - [pointerSocket]: socket separado, cujo endereço só é conhecido em
 *    runtime (a TV retorna via resposta SSAP a
 *    LgWebOsProtocol.URI_GET_POINTER_INPUT_SOCKET, enviada no socket
 *    principal). É por ele que os botões de navegação são enviados, em
 *    texto plano (não JSON) - ver LgWebOsProtocol.buildButtonCommand.
 *
 * Pareamento: equivalente ao "token" da Samsung, mas aqui chama-se
 * "client-key". Persistido via CredentialStore com
 * [Constants.LG_CREDENTIAL_TYPE] - nenhuma mudança foi necessária no
 * CredentialStore em si, ele já era genérico o suficiente.
 *
 * *** CORREÇÃO 3 (permissão do pointer socket) ***
 * Ver [LgWebOsProtocol] - CONTROL_MOUSE_AND_KEYBOARD/CONTROL_INPUT_TEXT
 * precisaram ser adicionadas também na lista de permissions de nível
 * superior do manifest (não só em "signed") para alguns modelos/firmwares
 * aceitarem getPointerInputSocket. Requer reparear a TV uma vez.
 *
 * *** CORREÇÃO 4 (paridade com Samsung: painel de diagnóstico + timeout) ***
 * Comparado a [com.example.smartremote.controller.samsung.SamsungTizenController],
 * esta classe só chamava DiagnosticManager.log() (histórico de eventos),
 * nunca os setters de ESTADO (updateDevice/setController/
 * setConnectionStatus/setToken/setLastError) - por isso o painel superior
 * (IP/Marca/Modelo/Status/Token) ficava sempre em "-"/"Desconectado" mesmo
 * com o pareamento completando com sucesso. Também não existia timeout de
 * pareamento (a Samsung tem schedulePairingTimeout via
 * Constants.SAMSUNG_PAIRING_TIMEOUT_MS) - se o usuário nunca confirmasse o
 * popup na TV, o app ficava esperando indefinidamente. Ambos corrigidos
 * agora seguindo exatamente o mesmo padrão da Samsung.
 */
class LgWebOsController(
    private val context: Context,
    private val device: TvDevice
) : TvController {

    private val mainSocket = LgWebOsSocketClient()
    private var pointerSocket: LgWebOsSocketClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: TvConnectionListener? = null
    private var connected = false
    private var pointerSocketReady = false
    private var pairingTimeoutRunnable: Runnable? = null

    /** Ver KDoc equivalente em SamsungTizenController - mesmo propósito. */
    @Volatile private var explicitDisconnect = false

    /** Ver KDoc de [TvController.connect]. */
    private var isAutomaticReconnect = false

    /**
     * Tradução RemoteKey -> nome de botão do pointer socket. Nomes
     * conforme documentados pelo binding webOS do Home Assistant e
     * confirmados contra libs de referência (lgtv2/webos-lib).
     *
     * RemoteKey.POWER propositalmente NÃO está neste mapa - ele não é um
     * botão do pointer socket, e sim uma requisição SSAP no socket
     * PRINCIPAL (ver [sendPowerOff]). Colocá-lo aqui faria sendRemoteKey
     * tentar mandar "type:button\nname:POWER..." pelo pointer socket, que
     * a TV simplesmente ignora - por isso o tratamento fica explícito e
     * separado, não escondido dentro do mapa.
     */
    /**
     * Apps de streaming lançáveis via [LgWebOsProtocol.URI_LAUNCH_APP].
     * Checado ANTES de [KEY_BUTTON_MAP] em [sendRemoteKey], mesma ordem
     * de prioridade usada por SamsungTizenController (App IDs e teclas
     * físicas são mecanismos diferentes, mas convergem no mesmo
     * sendRemoteKey - ver TvController.sendRemoteKey).
     */
    /**
     * Apps de streaming lançáveis via [LgWebOsProtocol.URI_LAUNCH_APP].
     * Checado ANTES de [KEY_BUTTON_MAP] em [sendRemoteKey], mesma ordem
     * de prioridade usada por SamsungTizenController (App IDs e teclas
     * físicas são mecanismos diferentes, mas convergem no mesmo
     * sendRemoteKey - ver TvController.sendRemoteKey).
     *
     * *** v0.9, item 2 ***: DISNEY_PLUS/APPLE_TV_PLUS/MAX adicionados
     * nesta versão - ver KDoc de cada *_APP_ID em [LgWebOsProtocol] para a
     * fonte de cada um. PARAMOUNT_PLUS/CRUNCHYROLL/GLOBOPLAY continuam
     * fora do mapa (sem App ID confiável encontrado para webOS) - ver o
     * mesmo KDoc.
     */
    private val APP_LAUNCH_MAP: Map<RemoteKey, String> = mapOf(
        RemoteKey.NETFLIX to LgWebOsProtocol.NETFLIX_APP_ID,
        RemoteKey.PRIME_VIDEO to LgWebOsProtocol.PRIME_VIDEO_APP_ID,
        RemoteKey.YOUTUBE to LgWebOsProtocol.YOUTUBE_APP_ID,
        RemoteKey.PLEX to LgWebOsProtocol.PLEX_APP_ID,
        RemoteKey.DISNEY_PLUS to LgWebOsProtocol.DISNEY_PLUS_APP_ID,
        RemoteKey.APPLE_TV_PLUS to LgWebOsProtocol.APPLE_TV_PLUS_APP_ID,
        RemoteKey.MAX to LgWebOsProtocol.MAX_APP_ID
    )

    private val KEY_BUTTON_MAP: Map<RemoteKey, String> = mapOf(
        RemoteKey.UP to "UP",
        RemoteKey.DOWN to "DOWN",
        RemoteKey.LEFT to "LEFT",
        RemoteKey.RIGHT to "RIGHT",
        RemoteKey.OK to "ENTER",
        RemoteKey.BACK to "BACK",
        RemoteKey.HOME to "HOME",
        RemoteKey.MUTE to "MUTE",
        RemoteKey.VOLUME_UP to "VOLUMEUP",
        RemoteKey.VOLUME_DOWN to "VOLUMEDOWN",
        RemoteKey.CHANNEL_UP to "CHANNELUP",
        RemoteKey.CHANNEL_DOWN to "CHANNELDOWN",

        // Faltava por completo - RemoteKey.PLAY_PAUSE não tinha nenhuma
        // entrada aqui, então sendRemoteKey() caía direto no bloco
        // "buttonName == null" e retornava sem nunca chegar a montar/enviar
        // nada pelo pointer socket. "PAUSE" é o nome de botão real e
        // documentado do protocolo SSAP (mesmo valor usado pela integração
        // webOS do Home Assistant) - não existe um botão nativo de toggle
        // "PLAY_PAUSE" no pointer socket, só os discretos PLAY/PAUSE, mesma
        // situação da Samsung.
        RemoteKey.PLAY_PAUSE to "PAUSE"
    )

    override fun connect(listener: TvConnectionListener, isAutomaticReconnect: Boolean) {
        this.listener = listener
        this.explicitDisconnect = false
        this.isAutomaticReconnect = isAutomaticReconnect

        val credentialDeviceId = device.deviceId ?: device.ip
        val savedClientKey = CredentialStore.get(context, credentialDeviceId, Constants.LG_CREDENTIAL_TYPE)

        // Preenche o painel de estado (IP/marca/modelo/SO/protocolo,
        // controlador ativo, status) - mesma convenção usada por
        // SamsungTizenController.connect(). Esta API é separada de
        // DiagnosticManager.log(): log() só alimenta o histórico de
        // eventos; estes setters alimentam o DiagnosticState lido pelo
        // painel de cima.
        DiagnosticManager.updateDevice(device)
        DiagnosticManager.setController(CONTROLLER_NAME)
        DiagnosticManager.setLastError(null)
        DiagnosticManager.setConnectionStatus(context.getString(R.string.status_connecting))
        if (!savedClientKey.isNullOrBlank()) {
            DiagnosticManager.setToken(savedClientKey)
        }

        DiagnosticManager.log(
            "LG webOS: iniciando conexão com ${device.ip} (deviceId=$credentialDeviceId, clientKey salvo? ${!savedClientKey.isNullOrBlank()})",
            DiagnosticLogType.INFO
        )

        if (isAutomaticReconnect && savedClientKey.isNullOrBlank()) {
            // *** v0.9, item 1 ***: mesma lógica da Samsung - reconexão
            // automática sem client-key salva abriria um popup de
            // pareamento na TV sem o usuário olhando. Desiste
            // silenciosamente e sinaliza "não recuperável".
            DiagnosticManager.log(
                "Reconexão automática cancelada: TV LG sem client-key salva (exigiria novo pareamento)",
                DiagnosticLogType.INFO
            )
            DiagnosticManager.setConnectionStatus(context.getString(R.string.status_disconnected))
            mainHandler.post { listener.onConnectionLost(recoverable = false) }
            return
        }

        mainSocket.connect(buildMainSocketUrl(device.ip), LgWebOsSocketClient.Listener { event ->
            // IMPORTANTE: este listener é chamado pelo OkHttp na thread de
            // leitura do próprio WebSocket (nome "OkHttp <url>"), nunca na
            // main thread. Todo o processamento - incluindo o parse e,
            // principalmente, as chamadas a TvConnectionListener (que
            // acabam tocando Views lá na Activity) - precisa ser
            // despachado para a main thread antes de prosseguir. Ver nota
            // "CORREÇÃO 2 (thread)" no topo do arquivo.
            mainHandler.post {
                when (event) {
                    is LgWebOsSocketClient.SocketEvent.Open -> {
                        DiagnosticManager.log("LG webOS: socket principal aberto, enviando register", DiagnosticLogType.INFO)
                        mainSocket.send(LgWebOsProtocol.buildRegisterMessage(savedClientKey))
                        DiagnosticManager.log("LG webOS: mensagem de register enviada", DiagnosticLogType.INFO)
                    }

                    is LgWebOsSocketClient.SocketEvent.Message -> {
                        DiagnosticManager.log("[LG-PAIRING] 1/6 - mensagem bruta recebida do socket principal: ${event.text}", DiagnosticLogType.INFO)
                        handleMainSocketMessage(event.text, credentialDeviceId)
                    }

                    is LgWebOsSocketClient.SocketEvent.Closed -> {
                        DiagnosticManager.log("LG webOS: socket principal fechado (${event.reason})", DiagnosticLogType.INFO)
                        val wasConnected = connected
                        connected = false
                        DiagnosticManager.setConnectionStatus(context.getString(R.string.status_disconnected))
                        if (wasConnected && !explicitDisconnect) {
                            // *** v0.9, item 1 ***: mesma lógica da Samsung
                            // - queda inesperada enquanto conectados,
                            // candidata a reconexão automática.
                            this.listener?.onConnectionLost(recoverable = true)
                        }
                    }

                    is LgWebOsSocketClient.SocketEvent.Failure -> {
                        cancelPairingTimeout()
                        DiagnosticManager.log("LG webOS: falha no socket principal - ${event.message}", DiagnosticLogType.ERROR)
                        val wasConnected = connected
                        connected = false
                        DiagnosticManager.setConnectionStatus(context.getString(R.string.status_disconnected))
                        DiagnosticManager.setLastError(event.message)
                        if (wasConnected && !explicitDisconnect) {
                            this.listener?.onConnectionLost(recoverable = true)
                        } else {
                            this.listener?.onError(context.getString(R.string.error_lg_connect_failed, event.message))
                        }
                    }
                }
            }
        })
    }

    private fun handleMainSocketMessage(raw: String, credentialDeviceId: String) {
        val lgEvent = LgWebOsProtocol.parseEvent(raw)
        DiagnosticManager.log(
            "[LG-PAIRING] 2/6 - LgWebOsProtocol.parseEvent() retornou: ${lgEvent::class.simpleName}",
            DiagnosticLogType.INFO
        )

        when (lgEvent) {
            is LgWebOsProtocol.LgEvent.Registered -> {
                cancelPairingTimeout()

                DiagnosticManager.log(
                    "[LG-PAIRING] 3/6 - type=\"registered\" confirmado; client-key presente no payload (tamanho=${lgEvent.clientKey.length}, prefixo=${lgEvent.clientKey.take(4)}...)",
                    DiagnosticLogType.INFO
                )

                DiagnosticManager.log("[LG-PAIRING] 4/6 - chamando CredentialStore.save(deviceId=$credentialDeviceId, type=${Constants.LG_CREDENTIAL_TYPE})", DiagnosticLogType.INFO)
                CredentialStore.save(context, credentialDeviceId, Constants.LG_CREDENTIAL_TYPE, lgEvent.clientKey)
                val verify = CredentialStore.get(context, credentialDeviceId, Constants.LG_CREDENTIAL_TYPE)
                DiagnosticManager.log(
                    "[LG-PAIRING] 4/6 - CredentialStore.save() concluído; releitura imediata confirma valor salvo? ${!verify.isNullOrBlank()}",
                    if (!verify.isNullOrBlank()) DiagnosticLogType.INFO else DiagnosticLogType.ERROR
                )
                DiagnosticManager.setToken(lgEvent.clientKey)

                connected = true
                DiagnosticManager.setConnectionStatus(context.getString(R.string.status_connected))
                DiagnosticManager.setLastError(null)

                DiagnosticManager.log("[LG-PAIRING] 5/6 - chamando listener?.onConnected() (listener nulo? ${listener == null})", DiagnosticLogType.INFO)
                listener?.onConnected()
                DiagnosticManager.log("[LG-PAIRING] 5/6 - listener?.onConnected() retornou (chamada síncrona concluída)", DiagnosticLogType.INFO)

                requestPointerSocket()
            }

            is LgWebOsProtocol.LgEvent.PairingRequired -> {
                DiagnosticManager.log("[LG-PAIRING] TV pedindo confirmação do usuário (popup exibido) - aguardando aceite", DiagnosticLogType.INFO)
                DiagnosticManager.setConnectionStatus(context.getString(R.string.status_waiting_tv_confirmation))
                listener?.onPairingRequired()
                schedulePairingTimeout()
            }

            is LgWebOsProtocol.LgEvent.PointerSocketReady -> {
                DiagnosticManager.log("LG webOS: endereço do pointer socket recebido - ${lgEvent.socketPath}", DiagnosticLogType.INFO)
                openPointerSocket(lgEvent.socketPath)
            }

            is LgWebOsProtocol.LgEvent.Error -> {
                // Cobre, entre outros casos, "registered" recebido SEM
                // client-key no payload (checkpoint 3/6 falhando), e
                // também erros de requisições SSAP posteriores ao
                // pareamento (ex: "401 insufficient permissions" no pedido
                // do pointer socket - ver LgWebOsProtocol.parseEvent()).
                // Não derruba a conexão nem chama onError() aqui: se já
                // estávamos "connected" (pareamento já tinha sido aceito),
                // isto é um erro de comando específico, não de conexão.
                DiagnosticManager.log("[LG-PAIRING] 3/6 - erro do protocolo (pode ser 'registered' sem client-key, ou erro explícito da TV) - ${lgEvent.message}", DiagnosticLogType.ERROR)
                DiagnosticManager.setLastError(lgEvent.message)
            }

            is LgWebOsProtocol.LgEvent.Unknown -> {
                DiagnosticManager.log("[LG-PAIRING] mensagem recebida não reconhecida por parseEvent (type=${lgEvent.type}) - ver mensagem bruta no checkpoint 1/6 acima", DiagnosticLogType.INFO)
            }
        }
    }

    /**
     * Pede à TV, via requisição SSAP no socket PRINCIPAL, o endereço do
     * pointer input socket. Só é chamado depois de [connected] = true
     * (registro aceito) - pedir antes disso é rejeitado pela TV.
     */
    private fun requestPointerSocket() {
        DiagnosticManager.log("LG webOS: solicitando pointer input socket", DiagnosticLogType.INFO)
        mainSocket.send(
            LgWebOsProtocol.buildRequestMessage(
                id = "pointer_input_0",
                uri = LgWebOsProtocol.URI_GET_POINTER_INPUT_SOCKET
            )
        )
    }

    private fun openPointerSocket(socketPath: String) {
        val client = LgWebOsSocketClient()
        pointerSocket = client
        client.connect(socketPath, LgWebOsSocketClient.Listener { event ->
            // Mesmo motivo do socket principal: callback do OkHttp roda
            // fora da main thread. Despachamos para manter o padrão do
            // resto do app e evitar qualquer acesso concorrente aos campos
            // (pointerSocketReady/connected) vindo de threads diferentes.
            mainHandler.post {
                when (event) {
                    is LgWebOsSocketClient.SocketEvent.Open -> {
                        DiagnosticManager.log("LG webOS: pointer socket conectado e pronto", DiagnosticLogType.INFO)
                        pointerSocketReady = true
                    }
                    is LgWebOsSocketClient.SocketEvent.Closed -> {
                        DiagnosticManager.log("LG webOS: pointer socket fechado (${event.reason})", DiagnosticLogType.INFO)
                        pointerSocketReady = false
                    }
                    is LgWebOsSocketClient.SocketEvent.Failure -> {
                        pointerSocketReady = false
                        DiagnosticManager.log("LG webOS: falha no pointer socket - ${event.message}", DiagnosticLogType.ERROR)
                    }
                    is LgWebOsSocketClient.SocketEvent.Message -> {
                        // O pointer socket não envia mensagens relevantes de volta nesta fase.
                    }
                }
            }
        })
    }

    override fun disconnect() {
        explicitDisconnect = true
        cancelPairingTimeout()
        DiagnosticManager.log("LG webOS: desconectando (pointer socket + socket principal)", DiagnosticLogType.INFO)
        pointerSocket?.close()
        pointerSocket = null
        pointerSocketReady = false
        mainSocket.close()
        connected = false
        DiagnosticManager.setConnectionStatus(context.getString(R.string.status_disconnected))
        listener = null
    }

    // ===================== TIMEOUT DO PAREAMENTO =====================
    // Mesmo padrão de SamsungTizenController.schedulePairingTimeout(): se
    // o usuário nunca confirmar o popup exibido na TV, o app não deve
    // ficar esperando indefinidamente.

    private fun schedulePairingTimeout() {
        cancelPairingTimeout()
        val runnable = Runnable {
            connected = false
            DiagnosticManager.setConnectionStatus(context.getString(R.string.status_disconnected))
            DiagnosticManager.log("Timeout aguardando confirmação na TV", DiagnosticLogType.ERROR)
            DiagnosticManager.setLastError(context.getString(R.string.error_lg_confirmation_timeout))
            mainSocket.close()
            listener?.onError(context.getString(R.string.error_lg_confirmation_timeout))
        }
        pairingTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, Constants.LG_PAIRING_TIMEOUT_MS)
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
    }

    override fun isConnected(): Boolean = connected

    override fun sendRemoteKey(key: RemoteKey) {
        if (!connected) {
            DiagnosticManager.log("Falha ao enviar comando: TV LG desconectada", DiagnosticLogType.ERROR)
            return
        }

        val appId = APP_LAUNCH_MAP[key]
        if (appId != null) {
            sendAppLaunch(key, appId)
            return
        }

        if (key == RemoteKey.POWER) {
            sendPowerOff()
            return
        }

        val buttonName = KEY_BUTTON_MAP[key]
        if (buttonName == null) {
            DiagnosticManager.log("Tecla $key ainda não suportada pela LG nesta fase", DiagnosticLogType.ERROR)
            return
        }

        if (!pointerSocketReady) {
            DiagnosticManager.log("Falha ao enviar $key: pointer socket ainda não está pronto", DiagnosticLogType.ERROR)
            return
        }

        val sent = pointerSocket?.send(LgWebOsProtocol.buildButtonCommand(buttonName)) ?: false
        DiagnosticManager.log(
            if (sent) "LG webOS: comando $key ($buttonName) enviado" else "LG webOS: falha ao enviar $key ($buttonName), pointer socket indisponível no momento do envio",
            if (sent) DiagnosticLogType.INFO else DiagnosticLogType.ERROR
        )
    }

    /**
     * POWER é tratado à parte porque não passa pelo pointer socket - é uma
     * requisição SSAP comum no socket PRINCIPAL ("ssap://system/turnOff").
     *
     * LIMITAÇÃO CONHECIDA E INTENCIONAL NESTA FASE: isto desliga a TV
     * (coloca em standby), mas não LIGA uma TV já desligada. Ligar de
     * verdade uma webOS TV desligada exige Wake-on-LAN (magic packet para
     * o MAC da TV) - o WebSocket de controle não fica disponível com a TV
     * totalmente desligada, então não há como "acordá-la" por este mesmo
     * socket. RemoteKey.POWER aqui sempre desliga; suporte a ligar via WoL
     * (exige guardar o MAC da TV, obtido na descoberta SSDP/mDNS) fica
     * para uma fase futura - diferente da Samsung, que hoje também não
     * implementa WoL, mas cujo comportamento de standby por rede pode
     * variar por modelo.
     */
    private fun sendPowerOff() {
        DiagnosticManager.log("LG webOS: enviando turnOff (socket principal)", DiagnosticLogType.INFO)
        mainSocket.send(
            LgWebOsProtocol.buildRequestMessage(
                id = "power_off_0",
                uri = LgWebOsProtocol.URI_SYSTEM_TURN_OFF
            )
        )
    }

    /**
     * Abre um app já instalado na TV via SSAP system.launcher/launch, no
     * socket PRINCIPAL (mecanismo diferente do pointer socket usado pelas
     * teclas de navegação - ver [LgWebOsProtocol.URI_LAUNCH_APP]).
     * Fire-and-forget, igual ao restante do protocolo: a TV não confirma
     * de volta se o app realmente abriu.
     */
    private fun sendAppLaunch(key: RemoteKey, appId: String) {
        DiagnosticManager.setLastCommand("Abrir app: ${key.name}")
        val payload = JSONObject().put("id", appId)
        val sent = mainSocket.send(
            LgWebOsProtocol.buildRequestMessage(
                id = "app_launch_${key.name.lowercase()}",
                uri = LgWebOsProtocol.URI_LAUNCH_APP,
                payload = payload
            )
        )

        if (!sent) {
            DiagnosticManager.log("Falha ao enviar comando: TV LG desconectada", DiagnosticLogType.ERROR)
            return
        }

        DiagnosticManager.log("LG webOS: aplicativo aberto: ${key.name} (appId=$appId)", DiagnosticLogType.NETWORK)
    }

    override fun sendText(text: String) {
        DiagnosticManager.log("Envio de texto ainda não suportado para LG webOS nesta fase", DiagnosticLogType.ERROR)
    }

    override fun supportedApps(): Set<RemoteKey> = APP_LAUNCH_MAP.keys

    private fun buildMainSocketUrl(ip: String): String =
        "wss://$ip:${Constants.LG_WS_PORT}"

    private companion object {
        const val CONTROLLER_NAME = "LgWebOsController"
    }
}
