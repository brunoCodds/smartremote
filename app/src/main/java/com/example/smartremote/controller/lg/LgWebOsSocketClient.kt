package com.example.smartremote.controller.lg

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
 * Encapsula UM WebSocket usado para falar com a Smart TV LG webOS
 * (protocolo SSAP). Estruturalmente idêntico ao SamsungSocketClient - a
 * diferença real fica em [LgWebOsController], que usa DUAS instâncias
 * desta classe: uma para o socket principal (porta fixa 3001) e outra
 * para o pointer input socket (endereço só conhecido em runtime, ver
 * LgWebOsProtocol.LgEvent.PointerSocketReady).
 *
 * Usa TrustManager que aceita qualquer certificado pela mesma razão da
 * Samsung: a porta 3001 da LG também serve um certificado autoassinado
 * gerado pela própria TV, e o destino é sempre o IP local da TV
 * escolhida na descoberta - nunca um host arbitrário da internet.
 */
class LgWebOsSocketClient {

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

    /**
     * Envia [text] pelo socket já aberto. Retorna `false` (sem lançar
     * exceção) se não houver socket aberto no momento - quem chama deve
     * tratar esse retorno como "não foi possível enviar agora", nunca
     * como erro fatal. Mesmo contrato do SamsungSocketClient.send().
     */
    fun send(text: String): Boolean {
        val socket = webSocket ?: return false
        return socket.send(text)
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
