package com.example.smartremote.controller.samsung

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Encapsula o WebSocket usado para falar com a Smart TV Samsung (porta
 * 8002, TLS).
 *
 * Essa porta usa um certificado autoassinado, gerado pela própria TV (não
 * emitido por nenhuma CA confiável) - isso é uma característica conhecida
 * do protocolo "Samsung Remote Control", replicada por todas as
 * implementações abertas dele. Por isso este cliente usa um TrustManager
 * que aceita qualquer certificado nesta conexão específica. Isso só é
 * aceitável porque o destino é sempre o IP da própria TV que o usuário
 * selecionou na descoberta local (SSDP/mDNS) - nunca um host arbitrário da
 * internet - e nenhuma outra chamada de rede do app usa este client.
 */
class SamsungSocketClient {

    fun interface Listener {
        fun onEvent(event: SocketEvent)
    }

    sealed class SocketEvent {
        object Open : SocketEvent()
        data class Message(val text: String) : SocketEvent()
        data class Closed(val reason: String) : SocketEvent()
        data class Failure(val message: String) : SocketEvent()
    }

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient by lazy { buildClient() }

    fun connect(url: String, listener: Listener) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onEvent(SocketEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onEvent(SocketEvent.Message(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onEvent(SocketEvent.Closed(reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onEvent(SocketEvent.Failure(t.message ?: "Erro desconhecido"))
            }
        })
    }

    /** Fecha a conexão atual, se existir. Seguro chamar mesmo sem conexão aberta. */
    fun close() {
        webSocket?.close(NORMAL_CLOSURE_CODE, null)
        webSocket = null
    }

    private fun buildClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // conexão fica aberta aguardando eventos assíncronos da TV
            .build()
    }

    private companion object {
        const val NORMAL_CLOSURE_CODE = 1000
    }
}
