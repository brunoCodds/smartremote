package com.example.smartremote.controller.androidtv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * *** NOVO - v0.9, item 3 (Android TV / Google TV) ***
 *
 * Encapsula a conexão TLS bruta usada pelo Android TV Remote v2 - bem
 * diferente do WebSocket usado por Samsung ([com.example.smartremote.controller.samsung.SamsungSocketClient])
 * e LG ([com.example.smartremote.controller.lg.LgWebOsSocketClient]): aqui
 * não existe handshake HTTP/WebSocket nenhum, é só um socket TCP com TLS
 * por cima, onde cada mensagem Protobuf é precedida por exatamente 1 byte
 * de tamanho (0-255) - ver KDoc de [AndroidTvRemoteProtocol] para a fonte
 * dessa informação de framing.
 *
 * Duas diferenças de segurança/autenticação em relação aos outros dois
 * clients, específicas deste protocolo:
 * - Certificado da TV: assim como Samsung/LG, é autoassinado e não
 *   confiável por nenhuma CA pública - mesma justificativa (rede local,
 *   destino sempre o IP escolhido na descoberta) - por isso o
 *   TrustManager aqui também aceita qualquer certificado.
 * - AUTENTICAÇÃO DO CLIENTE (nova - Samsung/LG não fazem isso): o Android
 *   TV exige que O APP TAMBÉM apresente um certificado TLS (mTLS) -
 *   [identity] vem de [AndroidTvKeystoreManager] e é injetado aqui através
 *   de um [X509ExtendedKeyManager] mínimo que sempre oferece a MESMA
 *   identidade (só temos uma por TV pareada - não faz sentido deixar a
 *   JVM escolher entre múltiplas, o que poderia escolher a identidade
 *   errada se o app já tiver pareado com mais de uma TV Android TV nesse
 *   dispositivo - ver KDoc de [SingleIdentityKeyManager]).
 */
class AndroidTvSocketClient {

    fun interface Listener {
        fun onEvent(event: SocketEvent)
    }

    sealed class SocketEvent {
        /** Handshake TLS concluído - inclui o certificado que a TV apresentou (necessário para [AndroidTvRemoteProtocol.computePairingSecret]). */
        data class Handshaked(val serverCertificate: X509Certificate) : SocketEvent()
        data class Message(val bytes: ByteArray) : SocketEvent()
        data class Closed(val reason: String) : SocketEvent()
        data class Failure(val message: String) : SocketEvent()
    }

    @Volatile private var socket: SSLSocket? = null
    private val writeLock = Any()

    /**
     * Conecta e já inicia a leitura em background - roda inteiramente em
     * uma thread própria (não usa OkHttp/executor compartilhado, já que
     * este não é um cliente HTTP) até [close] ser chamado ou o socket
     * cair. Todos os eventos são entregues via [listener] - quem chama
     * (AndroidTvController) é responsável por repassar para a main thread
     * se for atualizar UI/DiagnosticManager a partir deles, mesmo padrão
     * que os outros dois controllers já seguem com seus próprios Handlers.
     */
    fun connect(host: String, port: Int, identity: AndroidTvIdentity, listener: Listener) {
        Thread({
            var rawSocket: SSLSocket? = null
            try {
                val sslContext = buildSslContext(identity)
                rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                rawSocket.soTimeout = 0 // sem timeout de leitura - a conexão fica aberta aguardando eventos assíncronos da TV (pings, respostas de pareamento)
                rawSocket.startHandshake()
                socket = rawSocket

                val serverCertificate = rawSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                if (serverCertificate == null) {
                    listener.onEvent(SocketEvent.Failure("TV não apresentou certificado TLS"))
                    return@Thread
                }
                listener.onEvent(SocketEvent.Handshaked(serverCertificate))

                readLoop(rawSocket, listener)
            } catch (e: Exception) {
                listener.onEvent(SocketEvent.Failure(e.message ?: "Erro desconhecido ao conectar"))
            } finally {
                try { rawSocket?.close() } catch (_: Exception) {}
            }
        }, "AndroidTvSocketClient-${host}:${port}").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(rawSocket: SSLSocket, listener: Listener) {
        val input = rawSocket.inputStream
        while (!rawSocket.isClosed) {
            val lengthByte = input.read()
            if (lengthByte == -1) {
                listener.onEvent(SocketEvent.Closed("Conexão encerrada pela TV (EOF)"))
                return
            }
            val length = lengthByte and 0xFF
            if (length == 0) continue // mensagem vazia (ex: keepalive de framing) - ignora e continua lendo

            val buffer = ByteArray(length)
            var readTotal = 0
            while (readTotal < length) {
                val n = input.read(buffer, readTotal, length - readTotal)
                if (n == -1) {
                    listener.onEvent(SocketEvent.Closed("Conexão encerrada pela TV no meio de uma mensagem"))
                    return
                }
                readTotal += n
            }
            listener.onEvent(SocketEvent.Message(buffer))
        }
    }

    /**
     * Envia [messageBytes] (já serializado por [AndroidTvRemoteProtocol]),
     * prefixando com o byte de tamanho exigido pelo framing do protocolo.
     * Retorna `false` sem lançar exceção se não houver conexão aberta -
     * mesmo contrato de `send()` dos outros dois socket clients.
     *
     * @throws IllegalArgumentException se [messageBytes] passar de 255
     * bytes (framing de 1 byte não suporta mais que isso) - nunca deveria
     * acontecer com as mensagens deste protocolo (todas pequenas), mas é
     * melhor falhar alto e claro do que truncar silenciosamente.
     */
    fun send(messageBytes: ByteArray): Boolean {
        require(messageBytes.size <= 0xFF) {
            "Mensagem Android TV Remote excede o limite de 255 bytes do framing (${messageBytes.size} bytes)"
        }
        val currentSocket = socket ?: return false
        return try {
            synchronized(writeLock) {
                val output = currentSocket.outputStream
                output.write(messageBytes.size)
                output.write(messageBytes)
                output.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Fecha a conexão atual, se existir. Seguro chamar mesmo sem conexão aberta. */
    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun buildSslContext(identity: AndroidTvIdentity): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleIdentityKeyManager(identity)), trustAllCerts, SecureRandom())
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
    }
}

/**
 * [X509ExtendedKeyManager] mínimo que sempre oferece a MESMA identidade
 * (chave privada do Keystore + certificado autoassinado), ignorando
 * completamente a negociação normal de "qual alias escolher" da JVM.
 *
 * Por quê: `KeyStore.getInstance("AndroidKeyStore")` é um handle para o
 * Keystore INTEIRO do app (todas as TVs Android TV já pareadas, cada uma
 * com seu próprio alias - ver [AndroidTvKeystoreManager]) - se déssemos
 * esse KeyStore inteiro para um [javax.net.ssl.KeyManagerFactory] padrão,
 * a JVM poderia escolher QUALQUER uma dessas identidades para apresentar
 * à TV atual (a lógica padrão de seleção de alias não tem como saber qual
 * delas é "a certa" para esta conexão específica), o que quebraria o
 * pareamento se o usuário já tiver pareado com mais de uma TV Android TV.
 * Este KeyManager elimina essa ambiguidade: quem chama
 * [AndroidTvSocketClient.connect] já escolheu a identidade certa (a de
 * [AndroidTvKeystoreManager] para ESTA TV) antes de montar o
 * SSLContext, então não há nada para "escolher" aqui.
 */
private class SingleIdentityKeyManager(private val identity: AndroidTvIdentity) : X509ExtendedKeyManager() {

    private val alias = "androidtv-identity"

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
