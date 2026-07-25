package com.example.smartremote.controller.samsung

import android.util.Base64
import com.example.smartremote.util.Constants
import org.json.JSONObject

/**
 * Lógica pura do protocolo Samsung Remote Control (Tizen): monta as URLs de
 * conexão, interpreta as mensagens JSON recebidas da TV, e monta as
 * mensagens de comando enviadas a ela. Não abre nenhuma conexão - quem fala
 * com a rede é o [SamsungSocketClient]. Mantida separada para poder ser
 * testada/entendida isoladamente do WebSocket.
 *
 * Importante: esta classe só lida com códigos de tecla já traduzidos (ex:
 * "KEY_HOME") - ela não conhece o enum [com.example.smartremote.model.RemoteKey].
 * A tradução de RemoteKey -> código Samsung é responsabilidade do
 * SamsungTizenController (ver seu KEY_CODE_MAP), mantendo esta classe
 * focada só em "como o protocolo Samsung se parece", sem depender de um
 * conceito genérico do app.
 */
object SamsungProtocol {

    /** Resultado da interpretação de uma mensagem recebida da TV. */
    sealed class SamsungEvent {
        /**
         * TV autorizou a conexão. [token] só vem preenchido quando esta
         * mensagem é o resultado de um pareamento (usuário acabou de
         * aceitar o popup); em reconexões com token já sabido, costuma vir
         * vazio - a própria conexão bem-sucedida já é a confirmação.
         */
        data class Connected(val token: String?) : SamsungEvent()

        /** Usuário recusou o popup, ou o token salvo não é mais válido. */
        object Unauthorized : SamsungEvent()

        /** Mensagem reconhecida pelo JSON, mas sem tratamento específico nesta fase. */
        data class Unknown(val eventName: String?) : SamsungEvent()
    }

    /**
     * Monta a URL de conexão. Sem [token]: usada no primeiro pareamento (a
     * TV mostra o popup de autorização). Com [token]: usada para reconectar
     * direto, sem popup.
     */
    fun buildSocketUrl(ip: String, token: String?): String {
        val nameBase64 = encodeAppName()
        val base = "wss://$ip:${Constants.SAMSUNG_WS_PORT}${Constants.SAMSUNG_WS_PATH}?name=$nameBase64"
        return if (token.isNullOrBlank()) base else "$base&token=$token"
    }

    fun encodeAppName(): String =
        Base64.encodeToString(Constants.SAMSUNG_APP_NAME.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * Interpreta uma mensagem de texto recebida do WebSocket da TV.
     * Formato geral: {"event": "ms.channel.connect", "data": {"token": "..."}}
     */
    fun parseEvent(raw: String): SamsungEvent {
        return try {
            val json = JSONObject(raw)
            val eventName = json.optString("event")
            when (eventName) {
                "ms.channel.connect" -> {
                    val token = json.optJSONObject("data")
                        ?.optString("token")
                        ?.takeIf { it.isNotBlank() }
                    SamsungEvent.Connected(token)
                }
                "ms.channel.unauthorized", "ms.channel.timeOut" -> SamsungEvent.Unauthorized
                else -> SamsungEvent.Unknown(eventName.takeIf { it.isNotBlank() })
            }
        } catch (e: Exception) {
            SamsungEvent.Unknown(null)
        }
    }

    /**
     * Monta a mensagem oficial do protocolo Samsung Remote Control para
     * simular o clique de uma tecla física, dado o [keyCode] já traduzido
     * (ex: "KEY_UP", "KEY_HOME", "KEY_ENTER"). Formato oficial:
     *
     * {
     *   "method": "ms.remote.control",
     *   "params": {
     *     "Cmd": "Click",
     *     "DataOfCmd": "<keyCode>",
     *     "Option": "false",
     *     "TypeOfRemote": "SendRemoteKey"
     *   }
     * }
     */
    fun buildRemoteControlCommand(keyCode: String): String {
        val params = JSONObject().apply {
            put("Cmd", "Click")
            put("DataOfCmd", keyCode)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        }
        return JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", params)
        }.toString()
    }
}
