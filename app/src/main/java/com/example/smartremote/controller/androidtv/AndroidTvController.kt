package com.example.smartremote.controller.androidtv

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
import java.security.cert.X509Certificate

/**
 * *** NOVO - v0.9, item 3 (Android TV / Google TV) ***
 *
 * TvController para TVs Android TV/Google TV, via "Android TV Remote
 * Service v2" (o mesmo protocolo do app oficial "Google TV") - ver KDoc
 * completo do protocolo em [AndroidTvRemoteProtocol].
 *
 * Bem mais elaborado que Samsung/LG por duas razões estruturais do
 * próprio protocolo (não é escolha de implementação):
 * 1. Duas portas/duas conexões TLS separadas: 6467 só para pareamento,
 *    6466 só para a sessão já pareada - nunca as duas ao mesmo tempo.
 * 2. Pareamento não é um simples popup "Permitir?" na TV (como
 *    Samsung/LG) - a TV exibe um código de 6 dígitos que o USUÁRIO
 *    precisa digitar de volta no app (ver [TvConnectionListener.onPairingCodeRequired]
 *    / [submitPairingCode]).
 *
 * Fluxo (variante SEM identidade salva - primeiro pareamento):
 *  1. Gera (ou recupera) o par de chaves desta TV ([AndroidTvKeystoreManager]).
 *  2. Abre TLS na porta 6467, apresentando nosso certificado.
 *  3. Troca PairingRequest -> PairingOption -> PairingConfiguration com a
 *     TV (cada uma só é enviada depois do ACK da anterior).
 *  4. A TV passa a exibir um código de 6 dígitos - avisamos a UI via
 *     [TvConnectionListener.onPairingCodeRequired].
 *  5. Usuário digita o código -> [submitPairingCode] calcula o hash
 *     (ver [AndroidTvRemoteProtocol.computePairingSecret]) e, se bater,
 *     envia como PairingSecret.
 *  6. TV confirma (PairingSecretAck) -> pareamento concluído - fecha a
 *     porta 6467 e abre a 6466 (variante "com identidade salva" abaixo).
 *
 * Fluxo (variante COM identidade salva - toda reconexão seguinte,
 * incluindo automática, já que a identidade É a credencial - não existe
 * "token" separado aqui):
 *  1. Recupera a identidade já existente no Keystore.
 *  2. Abre TLS direto na porta 6466 (pula pareamento inteiramente - a TV
 *     já reconhece nosso certificado de sessões anteriores).
 *  3. Envia RemoteConfigure + RemoteSetActive -> conectado.
 *
 * Assim como Samsung/LG, `TvManager`/`ConnectionManager`/UI continuam sem
 * saber nada sobre este protocolo específico - tudo fica encapsulado
 * aqui, e a comunicação com o resto do app é só via [TvController]/
 * [TvConnectionListener], exatamente como os outros dois fabricantes.
 */
class AndroidTvController(
    private val context: Context,
    private val device: TvDevice
) : TvController {

    private val credentialDeviceId = device.deviceId ?: device.ip
    private val keystoreManager = AndroidTvKeystoreManager(context, credentialDeviceId)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pairingSocket: AndroidTvSocketClient? = null
    private var remoteSocket: AndroidTvSocketClient? = null
    private var currentListener: TvConnectionListener? = null
    private var pairingTimeoutRunnable: Runnable? = null

    /** Certificado que a TV apresentou durante o handshake da porta 6467 - necessário para calcular o hash do código (ver [AndroidTvRemoteProtocol.computePairingSecret]). Só existe durante uma sessão de pareamento ativa. */
    private var serverCertificateDuringPairing: X509Certificate? = null
    private var identityBeingPaired: AndroidTvIdentity? = null

    @Volatile private var connected = false

    /** Ver KDoc equivalente em SamsungTizenController - mesmo propósito. */
    @Volatile private var explicitDisconnect = false
    private var isAutomaticReconnect = false

    override fun connect(listener: TvConnectionListener, isAutomaticReconnect: Boolean) {
        currentListener = listener
        explicitDisconnect = false
        this.isAutomaticReconnect = isAutomaticReconnect

        DiagnosticManager.updateDevice(device)
        DiagnosticManager.setController(CONTROLLER_NAME)
        DiagnosticManager.setLastError(null)
        DiagnosticManager.setConnectionStatus("Conectando")

        val existingIdentity = keystoreManager.existingIdentity()
        if (existingIdentity != null) {
            DiagnosticManager.log("Android TV: identidade já pareada encontrada - conectando direto na sessão de controle (porta ${Constants.ANDROID_TV_REMOTE_PORT})", DiagnosticLogType.INFO)
            connectRemoteSession(existingIdentity)
            return
        }

        if (isAutomaticReconnect) {
            // Ver KDoc de TvController.connect() - mesma regra de
            // Samsung/LG: reconexão automática sem credencial (aqui,
            // "credencial" = identidade no Keystore) nunca pode iniciar
            // um pareamento novo sozinha (exigiria o usuário digitar um
            // código que ele nem sabe que está esperando).
            DiagnosticManager.log("Reconexão automática cancelada: TV Android TV sem identidade pareada (exigiria pareamento manual com código)", DiagnosticLogType.INFO)
            notifyConnectionLost(recoverable = false)
            return
        }

        startPairing()
    }

    override fun disconnect() {
        explicitDisconnect = true
        cancelPairingTimeout()
        pairingSocket?.close()
        pairingSocket = null
        remoteSocket?.close()
        remoteSocket = null
        serverCertificateDuringPairing = null
        identityBeingPaired = null
        connected = false
        DiagnosticManager.setConnectionStatus("Desconectado")
        DiagnosticManager.log("Android TV: desconectado", DiagnosticLogType.INFO)
    }

    override fun isConnected(): Boolean = connected

    // ===================== Pareamento (porta 6467) =====================

    private fun startPairing() {
        DiagnosticManager.setConnectionStatus("Pareando")
        val identity = keystoreManager.ensureKeyPair()
        identityBeingPaired = identity

        val client = AndroidTvSocketClient()
        pairingSocket = client
        client.connect(device.ip, Constants.ANDROID_TV_PAIRING_PORT, identity) { event ->
            handlePairingSocketEvent(event)
        }

        schedulePairingTimeout()
    }

    private fun handlePairingSocketEvent(event: AndroidTvSocketClient.SocketEvent) {
        when (event) {
            is AndroidTvSocketClient.SocketEvent.Handshaked -> {
                serverCertificateDuringPairing = event.serverCertificate
                DiagnosticManager.log("Android TV: handshake TLS de pareamento concluído, enviando PairingRequest", DiagnosticLogType.NETWORK)
                pairingSocket?.send(
                    AndroidTvRemoteProtocol.buildPairingRequestMessage(
                        Constants.ANDROID_TV_SERVICE_NAME,
                        Constants.ANDROID_TV_CLIENT_NAME
                    )
                )
            }

            is AndroidTvSocketClient.SocketEvent.Message -> handlePairingMessage(event.bytes)

            is AndroidTvSocketClient.SocketEvent.Closed -> {
                if (!connected) {
                    // Fechou durante o próprio pareamento (antes de
                    // qualquer sessão remota existir) - nunca é uma queda
                    // "recuperável" por reconexão automática (pareamento
                    // nunca acontece em isAutomaticReconnect=true), então
                    // sempre trata como erro simples para o usuário ver.
                    cancelPairingTimeout()
                    notifyError("Conexão de pareamento encerrada pela TV antes de concluir")
                }
            }

            is AndroidTvSocketClient.SocketEvent.Failure -> {
                cancelPairingTimeout()
                DiagnosticManager.log("Android TV: falha no socket de pareamento - ${event.message}", DiagnosticLogType.ERROR)
                notifyError("Falha ao parear com a TV: ${event.message}")
            }
        }
    }

    private fun handlePairingMessage(bytes: ByteArray) {
        when (val parsed = AndroidTvRemoteProtocol.parsePairingMessage(bytes)) {
            is AndroidTvRemoteProtocol.PairingEvent.Ack -> when (parsed.kind) {
                AndroidTvRemoteProtocol.PairingAckKind.REQUEST -> {
                    DiagnosticManager.log("Android TV: PairingRequest confirmado, enviando PairingOption", DiagnosticLogType.RESPONSE)
                    pairingSocket?.send(AndroidTvRemoteProtocol.buildPairingOptionMessage())
                }

                AndroidTvRemoteProtocol.PairingAckKind.OPTION -> {
                    DiagnosticManager.log("Android TV: PairingOption confirmado, enviando PairingConfiguration", DiagnosticLogType.RESPONSE)
                    pairingSocket?.send(AndroidTvRemoteProtocol.buildPairingConfigurationMessage())
                }

                AndroidTvRemoteProtocol.PairingAckKind.CONFIGURATION -> {
                    // A partir daqui a TV já está exibindo o código de 6
                    // dígitos na tela - não faz mais sentido ter um
                    // timeout curto rodando (o usuário pode levar um
                    // tempo para olhar a TV e digitar) - cancelamos e
                    // deixamos sem timeout até o código ser enviado ou o
                    // usuário desistir (fechar a tela).
                    cancelPairingTimeout()
                    DiagnosticManager.log("Android TV: TV exibindo código de pareamento - aguardando o usuário digitar no app", DiagnosticLogType.INFO)
                    mainHandler.post { currentListener?.onPairingCodeRequired() }
                }

                AndroidTvRemoteProtocol.PairingAckKind.SECRET -> {
                    DiagnosticManager.log("Android TV: pareamento concluído - TV confirmou o código", DiagnosticLogType.RESPONSE)
                    val identity = identityBeingPaired
                    pairingSocket?.close()
                    pairingSocket = null
                    serverCertificateDuringPairing = null
                    if (identity != null) {
                        connectRemoteSession(identity)
                    } else {
                        notifyError("Erro interno: identidade de pareamento perdida")
                    }
                }
            }

            is AndroidTvRemoteProtocol.PairingEvent.Error -> {
                DiagnosticManager.log("Android TV: TV rejeitou o pareamento (status=${parsed.status})", DiagnosticLogType.ERROR)
                notifyError("A TV rejeitou o pareamento (código incorreto ou expirado)")
            }

            AndroidTvRemoteProtocol.PairingEvent.Unknown -> {
                DiagnosticManager.log("Android TV: mensagem de pareamento não reconhecida, ignorando", DiagnosticLogType.INFO)
            }
        }
    }

    /**
     * Chamado pela UI depois que o usuário digita o código de 6 dígitos
     * exibido na TV (em resposta a [TvConnectionListener.onPairingCodeRequired]).
     * Ver KDoc completo do algoritmo em [AndroidTvRemoteProtocol.computePairingSecret].
     */
    override fun submitPairingCode(code: String) {
        val identity = identityBeingPaired
        val serverCertificate = serverCertificateDuringPairing
        if (identity == null || serverCertificate == null || pairingSocket == null) {
            DiagnosticManager.log("Android TV: submitPairingCode() chamado fora de uma sessão de pareamento ativa - ignorado", DiagnosticLogType.WARNING)
            return
        }

        when (val result = AndroidTvRemoteProtocol.computePairingSecret(identity.certificate, serverCertificate, code)) {
            is AndroidTvRemoteProtocol.PairingSecretResult.Valid -> {
                DiagnosticManager.log("Android TV: código conferido localmente, enviando PairingSecret para a TV", DiagnosticLogType.COMMAND)
                pairingSocket?.send(AndroidTvRemoteProtocol.buildPairingSecretMessage(result.secret))
            }

            AndroidTvRemoteProtocol.PairingSecretResult.InvalidCode -> {
                DiagnosticManager.log("Android TV: código digitado não confere com o certificado da TV", DiagnosticLogType.ERROR)
                // *** Trade-off assumido aqui (documentado para
                // validação) ***: em vez de reabrir o diálogo para uma
                // nova tentativa imediata (exigiria um canal de
                // mensagem de erro específico no onPairingCodeRequired,
                // que hoje não carrega parâmetro nenhum), tratamos como
                // erro terminal desta tentativa de conexão - o usuário
                // toca "Conectar" de novo, o que abre uma sessão de
                // pareamento nova (a TV também gera um código novo). Como
                // o usuário está literalmente olhando o código na tela
                // no momento de digitar, um erro de digitação tende a
                // ser raro o bastante para essa simplificação valer a
                // pena nesta versão.
                notifyError("Código incorreto - confira o código exibido na TV e tente conectar de novo")
            }

            AndroidTvRemoteProtocol.PairingSecretResult.UnsupportedKeyType -> {
                // Não deveria acontecer - AndroidTvKeystoreManager sempre gera RSA.
                DiagnosticManager.log("Android TV: tipo de chave inesperado durante o pareamento", DiagnosticLogType.ERROR)
                notifyError("Erro interno de pareamento (tipo de chave)")
            }
        }
    }

    private fun schedulePairingTimeout() {
        cancelPairingTimeout()
        val runnable = Runnable {
            DiagnosticManager.log("Android TV: tempo esgotado aguardando confirmação da TV", DiagnosticLogType.ERROR)
            pairingSocket?.close()
            pairingSocket = null
            notifyError("Tempo esgotado ao parear com a TV")
        }
        pairingTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, Constants.ANDROID_TV_PAIRING_TIMEOUT_MS)
    }

    private fun cancelPairingTimeout() {
        pairingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
    }

    // ===================== Sessão de controle (porta 6466) =====================

    private fun connectRemoteSession(identity: AndroidTvIdentity) {
        DiagnosticManager.setConnectionStatus("Conectando")
        val client = AndroidTvSocketClient()
        remoteSocket = client
        client.connect(device.ip, Constants.ANDROID_TV_REMOTE_PORT, identity) { event ->
            handleRemoteSocketEvent(event)
        }
    }

    private fun handleRemoteSocketEvent(event: AndroidTvSocketClient.SocketEvent) {
        when (event) {
            is AndroidTvSocketClient.SocketEvent.Handshaked -> {
                DiagnosticManager.log("Android TV: handshake TLS da sessão de controle concluído, enviando RemoteConfigure", DiagnosticLogType.NETWORK)
                remoteSocket?.send(AndroidTvRemoteProtocol.buildRemoteConfigureMessage())
                remoteSocket?.send(AndroidTvRemoteProtocol.buildRemoteSetActiveMessage())
                connected = true
                DiagnosticManager.setConnectionStatus("Conectado")
                notifyConnected()
            }

            is AndroidTvSocketClient.SocketEvent.Message -> when (val parsed = AndroidTvRemoteProtocol.parseRemoteMessage(event.bytes)) {
                is AndroidTvRemoteProtocol.RemoteEvent.PingRequest -> {
                    // Keepalive da sessão - se não respondermos, a TV
                    // derruba a conexão por inatividade (mesma ideia de
                    // um "ping/pong" de WebSocket, só que modelado como
                    // mensagens de protocolo explícitas aqui).
                    remoteSocket?.send(AndroidTvRemoteProtocol.buildRemotePingResponseMessage(parsed.val1))
                }

                AndroidTvRemoteProtocol.RemoteEvent.Configured -> {
                    DiagnosticManager.log("Android TV: RemoteConfigure confirmado pela TV", DiagnosticLogType.RESPONSE)
                }

                AndroidTvRemoteProtocol.RemoteEvent.Unknown -> Unit // mensagens não usadas por este app (voz, IME, etc.) - ignoradas silenciosamente
            }

            is AndroidTvSocketClient.SocketEvent.Closed -> onRemoteSessionLost(reason = event.reason)
            is AndroidTvSocketClient.SocketEvent.Failure -> onRemoteSessionFailure(event.message)
        }
    }

    private fun onRemoteSessionLost(reason: String) {
        val wasConnected = connected
        connected = false
        DiagnosticManager.setConnectionStatus("Desconectado")
        DiagnosticManager.log("Android TV: sessão de controle encerrada ($reason)", DiagnosticLogType.NETWORK)

        if (wasConnected && !explicitDisconnect) {
            // *** v0.9, item 1 ***: mesma lógica de Samsung/LG - queda
            // inesperada enquanto conectados, candidata a reconexão
            // automática (a identidade continua salva no Keystore, então
            // a próxima tentativa pula o pareamento de novo).
            notifyConnectionLost(recoverable = true)
        }
    }

    private fun onRemoteSessionFailure(message: String) {
        val wasConnected = connected
        connected = false
        DiagnosticManager.setConnectionStatus("Desconectado")
        DiagnosticManager.setLastError(message)
        DiagnosticManager.log("Android TV: falha na sessão de controle - $message", DiagnosticLogType.ERROR)

        if (wasConnected && !explicitDisconnect) {
            notifyConnectionLost(recoverable = true)
            return
        }

        // Falha logo ao tentar abrir a sessão (não estávamos conectados
        // ainda) usando uma identidade JÁ pareada anteriormente. Duas
        // causas bem diferentes cabem aqui, e o protocolo não distingue
        // uma da outra de forma limpa via texto de exceção:
        // (a) rede/TV temporariamente inacessível -> vale reconexão automática.
        // (b) a TV "esqueceu" nosso certificado (reset de fábrica,
        //     desparelhamento manual do lado da TV) -> reconectar não
        //     adianta nunca, precisaria de um pareamento novo (com o
        //     usuário olhando a TV de novo).
        // *** Trade-off assumido aqui (documentado para validação) ***:
        // como não há um sinal confiável e portável para diferenciar (a)
        // de (b) só pela mensagem da exceção TLS, tratamos sempre como
        // (a) - continua tentando com backoff. Na prática, (b) é raro
        // (usuário teria que resetar a TV ou desparelhar manualmente) e,
        // se acontecer, as tentativas automáticas simplesmente continuam
        // falhando sem incomodar ninguém (nenhum popup aparece na TV,
        // diferente de Samsung/LG, já que reabrir a porta 6466 sem
        // certificado reconhecido não dispara nenhum prompt) - o usuário
        // só precisa esquecer e re-parear a TV manualmente quando notar
        // que nunca reconecta.
        if (isAutomaticReconnect) {
            notifyConnectionLost(recoverable = true)
        } else {
            notifyError("Falha ao conectar na TV Android TV: $message")
        }
    }

    // ===================== Comandos =====================

    override fun sendRemoteKey(key: RemoteKey) {
        if (!connected) {
            DiagnosticManager.log("Android TV: comando '$key' ignorado - TV desconectada", DiagnosticLogType.WARNING)
            return
        }

        val deepLink = AndroidTvRemoteProtocol.APP_DEEP_LINKS[key]
        if (deepLink != null) {
            DiagnosticManager.setLastCommand("APP_LINK($key -> $deepLink)")
            DiagnosticManager.log("Android TV: abrindo app via deep link ($deepLink)", DiagnosticLogType.COMMAND)
            remoteSocket?.send(AndroidTvRemoteProtocol.buildRemoteAppLinkLaunchMessage(deepLink))
            return
        }

        val keyCode = AndroidTvRemoteProtocol.REMOTE_KEY_TO_KEYCODE[key]
        if (keyCode != null) {
            DiagnosticManager.setLastCommand("KEY($key -> $keyCode)")
            DiagnosticManager.log("Android TV: enviando tecla $key (keycode $keyCode)", DiagnosticLogType.COMMAND)
            remoteSocket?.send(AndroidTvRemoteProtocol.buildRemoteKeyInjectMessage(keyCode))
            return
        }

        DiagnosticManager.log("Android TV: tecla '$key' não suportada por este controller", DiagnosticLogType.WARNING)
    }

    override fun sendText(text: String) {
        // *** v0.9, item 3 ***: o protocolo v2 tem mensagens de IME
        // (RemoteImeKeyInject/RemoteImeBatchEdit) para isso, mas o
        // formato exato de campo a campo não tem documentação pública
        // confiável o bastante para implementar com segurança nesta
        // versão (mesma situação de sendText() no LgWebOsController hoje -
        // ver KDoc lá). Documentado como limitação conhecida em vez de
        // arriscar uma implementação não testável às cegas.
        DiagnosticManager.log("Android TV: envio de texto ainda não suportado nesta versão", DiagnosticLogType.WARNING)
    }

    override fun supportedApps(): Set<RemoteKey> = AndroidTvRemoteProtocol.APP_DEEP_LINKS.keys

    // ===================== Notificações ao listener (main thread) =====================

    private fun notifyConnected() = mainHandler.post { currentListener?.onConnected() }
    private fun notifyError(message: String) = mainHandler.post { currentListener?.onError(message) }
    private fun notifyConnectionLost(recoverable: Boolean) =
        mainHandler.post { currentListener?.onConnectionLost(recoverable) }

    private companion object {
        const val CONTROLLER_NAME = "AndroidTvController"
    }
}
