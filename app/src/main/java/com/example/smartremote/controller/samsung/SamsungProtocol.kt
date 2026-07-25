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

    // App IDs para lançamento de apps via ms.channel.emit (ver
    // buildAppLaunchCommand). Centralizados aqui, e não em Constants.kt,
    // porque são um detalhe do protocolo Samsung especificamente - outra
    // marca terá seus próprios IDs em seu próprio *Protocol.
    //
    // IMPORTANTE - confiabilidade real destes IDs: a Samsung não publica
    // uma lista oficial de App IDs. Os valores abaixo vêm de um
    // levantamento comunitário (mesma fonte usada por projetos como
    // samsungtvws/Home Assistant - github.com/xchwarze/samsung-tv-ws-api,
    // arquivo APPLICATIONS.md) e o próprio levantamento avisa: "IDs are
    // not guaranteed to work on your TV" e podem mudar por ano/firmware
    // (muitos mudaram a partir de 2020). Por isso cada app abaixo guarda
    // uma LISTA de IDs candidatos (não um só) - o mecanismo de lançamento
    // (ver SamsungTizenController.sendAppLaunch) manda TODOS em sequência
    // pela mesma sessão: como o protocolo é fire-and-forget e um ID
    // desconhecido é simplesmente ignorado pela TV (sem erro nenhum),
    // isso não tem efeito colateral - só aumenta a chance de acertar o ID
    // certo para o modelo/firmware do usuário. É a mesma estratégia usada
    // pelas integrações de referência (elas também guardam mais de um ID
    // por app pelo mesmo motivo).
    //
    // CRUNCHYROLL propositalmente NÃO está aqui: é um app oficial
    // recente (rollout parcial por região/ano de TV a partir de 2024) e
    // não há nenhum App ID confiável documentado em nenhuma fonte
    // encontrada até esta fase. RemoteKey.CRUNCHYROLL existe no enum,
    // mas SamsungTizenController.APP_LAUNCH_MAP não tem entrada para ele
    // - a UI trata isso como "não suportado nesta TV" via
    // TvController.supportedApps(), até surgir um ID confiável.
    val NETFLIX_APP_IDS = listOf("11101200001", "3201907018807")
    val PRIME_VIDEO_APP_IDS = listOf("3201512006785", "3201910019365")
    val GLOBOPLAY_APP_IDS = listOf("3201908019022")
    val YOUTUBE_APP_IDS = listOf("111299001912")
    val DISNEY_PLUS_APP_IDS = listOf("3201901017640", "3202009021709", "3202204027038")
    /** HBO Max renomeado para "Max" - IDs do app antigo (HBO Max) e do atual (Max). */
    val MAX_APP_IDS = listOf("3201601007230", "3202301029760")
    /** App oficial "Apple TV" - hoje é onde o conteúdo do Apple TV+ é acessado. */
    val APPLE_TV_PLUS_APP_IDS = listOf("3201807016597")
    val PARAMOUNT_PLUS_APP_IDS = listOf("3201710014981", "3202110025305")
    val PLEX_APP_IDS = listOf("3201512006963")

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

    /**
     * Monta a mensagem oficial do protocolo Samsung para enviar um texto
     * livre de uma vez (não simula tecla por tecla). Formato oficial:
     *
     * {
     *   "method": "ms.remote.control",
     *   "params": {
     *     "Cmd": "<texto em Base64>",
     *     "DataOfCmd": "base64",
     *     "TypeOfRemote": "SendInputString"
     *   }
     * }
     *
     * Limitação conhecida do protocolo: não existe forma de consultar se
     * há um campo de texto realmente focado na TV no momento do envio -
     * se não houver, a TV apenas ignora silenciosamente, sem retornar
     * nenhum evento de erro. Quem chama esta função não deve assumir
     * confirmação de entrega além do envio em si.
     */
    fun buildSendTextCommand(text: String): String {
        val base64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val params = JSONObject().apply {
            put("Cmd", base64Text)
            put("DataOfCmd", "base64")
            put("TypeOfRemote", "SendInputString")
        }
        return JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", params)
        }.toString()
    }

    /**
     * Monta a mensagem oficial do protocolo Samsung para abrir um app
     * instalado na TV, dado seu [appId] (ver [NETFLIX_APP_IDS] /
     * [PRIME_VIDEO_APP_IDS] e demais *_APP_IDS). Mecanismo diferente do envio de tecla -
     * usa ms.channel.emit / ed.apps.launch:
     *
     * {
     *   "method": "ms.channel.emit",
     *   "params": {
     *     "event": "ed.apps.launch",
     *     "to": "host",
     *     "data": { "action_type": "NATIVE_LAUNCH", "appId": "<appId>", "metaTag": "" }
     *   }
     * }
     *
     * "action_type" e "metaTag" NÃO são opcionais - a versão anterior desta
     * função só mandava "appId", e várias TVs simplesmente ignoram (sem
     * erro nenhum) um ed.apps.launch fora desse formato. Confirmado
     * comparando com a lib de referência samsungtvws (ChannelEmitCommand.
     * launch_app). "NATIVE_LAUNCH" é o valor correto aqui porque Netflix e
     * Prime Video são apps já instalados na TV, não um link de conteúdo
     * web (que usaria "DEEP_LINK").
     */
    fun buildAppLaunchCommand(appId: String): String {
        val data = JSONObject().apply {
            put("action_type", "NATIVE_LAUNCH")
            put("appId", appId)
            put("metaTag", "")
        }
        val params = JSONObject().apply {
            put("event", "ed.apps.launch")
            put("to", "host")
            put("data", data)
        }
        return JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", params)
        }.toString()
    }

    /**
     * Aviso (broadcast) que deve ser enviado uma vez, antes do PRIMEIRO
     * [buildSendTextCommand] de uma sessão de digitação. Sem isso, várias
     * TVs ignoram silenciosamente o SendInputString que vem em seguida -
     * confirmado no fluxo oficial do próprio app Samsung SmartView
     * (RemoteControl.sendInputString sempre manda este broadcast antes do
     * primeiro texto de cada sessão). Quem decide "é a primeira vez desta
     * sessão?" é o SamsungTizenController, não esta função - aqui só a
     * mensagem em si.
     *
     * {
     *   "method": "ms.channel.emit",
     *   "params": { "event": "custom.remote.textReceived", "to": "broadcast" }
     * }
     */
    fun buildTextReceivedBroadcastCommand(): String {
        val params = JSONObject().apply {
            put("event", "custom.remote.textReceived")
            put("to", "broadcast")
        }
        return JSONObject().apply {
            put("method", "ms.channel.emit")
            put("params", params)
        }.toString()
    }

    /**
     * Finaliza/aplica a sessão de entrada de texto (IME) na TV. Deve ser
     * enviado DEPOIS de [buildSendTextCommand] - sem ele, a sessão de IME
     * fica pendurada e a TV pode nunca aplicar o texto no campo, mesmo
     * tendo recebido o SendInputString corretamente. Mesmo fluxo do app
     * oficial: sendInputString() sempre seguido de sendInputEnd().
     *
     * {
     *   "method": "ms.remote.control",
     *   "params": { "TypeOfRemote": "SendInputEnd" }
     * }
     */
    fun buildSendTextEndCommand(): String {
        val params = JSONObject().apply {
            put("TypeOfRemote", "SendInputEnd")
        }
        return JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", params)
        }.toString()
    }
}
