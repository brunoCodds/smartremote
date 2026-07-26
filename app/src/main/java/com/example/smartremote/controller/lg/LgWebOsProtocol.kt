package com.example.smartremote.controller.lg

import com.example.smartremote.util.Constants
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lógica pura do protocolo SSAP (Second Screen Application Protocol) usado
 * pelas TVs LG webOS: monta a mensagem de registro/pareamento, monta
 * requisições SSAP genéricas, interpreta as mensagens recebidas da TV, e
 * monta o comando de botão do pointer socket. Não abre nenhuma conexão -
 * quem fala com a rede é o [LgWebOsSocketClient]. Mesma separação de
 * responsabilidades usada em SamsungProtocol/SamsungSocketClient.
 *
 * Diferença importante frente ao protocolo Samsung: aqui existem DOIS
 * "formatos" de mensagem em uso -
 *  - JSON, para tudo que acontece no socket PRINCIPAL (registro,
 *    requisições SSAP como "ssap://system/turnOff" ou o pedido do pointer
 *    socket);
 *  - texto plano ("type:button\nname:<BOTAO>\n\n"), só para o socket
 *    separado de botões (pointer input socket), cujo endereço a própria
 *    TV informa em runtime (ver [LgEvent.PointerSocketReady]).
 *
 * A tradução RemoteKey -> nome de botão LG (e a decisão de qual socket
 * usar para cada RemoteKey) é responsabilidade do LgWebOsController, não
 * desta classe - igual ao papel do KEY_CODE_MAP na Samsung.
 *
 * ATENÇÃO - manifest assinado (ETAPA 1 - correção de bug crítico):
 * O manifest enviado em [buildRegisterMessage] PRECISA conter um bloco
 * "signatures" com uma assinatura RSA sobre um conteúdo específico de
 * "signed" (appId, vendorId, nomes localizados, permissions e serial).
 * Essa assinatura é um valor público e fixo, gerado contra um certificado
 * de teste ("test-signing-cert") que ainda é aceito pelo firmware webOS -
 * é o mesmo valor usado por lgtv2, aiowebostv, bscpylgtv e pela
 * integração oficial webOS do Home Assistant (todos projetos
 * open-source). SEM esse bloco "signatures", ou COM ele mas com o
 * conteúdo de "signed" alterado (ex: appId/vendorId/nomes diferentes dos
 * usados por essas referências), a TV rejeita o registro silenciosamente:
 * o WebSocket abre e permanece aberto normalmente, mas a TV nunca chega a
 * emitir a mensagem "response" com pairingType=PROMPT - ou seja, o popup
 * de autorização nunca aparece na tela. Esse foi exatamente o bug
 * encontrado nesta fase (versão anterior desta função customizava
 * appId/vendorId/localizedAppNames e não tinha "signatures").
 *
 * CONSEQUÊNCIA ACEITA: como a assinatura é fixa e pública, o texto que
 * aparece no popup da TV ("<nome> quer se conectar") é sempre o nome
 * genérico usado pelas libs de referência ("LG Remote App"), não
 * [Constants.LG_APP_NAME]. Não é possível assinar um manifest customizado
 * sem uma chave privada real da LG - essa é a mesma limitação que
 * lgtv2/aiowebostv/Home Assistant têm.
 */
object LgWebOsProtocol {

    /** URI SSAP (socket principal) que retorna o endereço do pointer input socket. */
    const val URI_GET_POINTER_INPUT_SOCKET = "ssap://com.webos.service.networkinput/getPointerInputSocket"

    /**
     * URI SSAP (socket principal) para desligar a TV (standby). Não liga
     * uma TV já desligada - ver limitação documentada em
     * LgWebOsController.sendPowerOff().
     */
    const val URI_SYSTEM_TURN_OFF = "ssap://system/turnOff"

    /**
     * URI SSAP (socket principal) para abrir um app já instalado na TV.
     * Payload: {"id": "<appId>"} - documentada oficialmente pela LG
     * (webostv.developer.lge.com/api/webos-service-api/application-manager)
     * e usada da mesma forma por lgtv2 (lgtv.request('ssap://system.launcher/launch', {id: 'netflix'})).
     * Requer a permissão "LAUNCH", já presente em [PERMISSIONS].
     */
    const val URI_LAUNCH_APP = "ssap://system.launcher/launch"

    /**
     * App IDs do webOS para lançamento via [URI_LAUNCH_APP]. Diferente da
     * Samsung (App IDs numéricos sem fonte oficial - ver SamsungProtocol),
     * aqui cada ID abaixo tem confirmação relativamente direta:
     * - NETFLIX/YOUTUBE: exemplos oficiais de luna-send citados em notas
     *   públicas de hacking do webOS (id "netflix" e "youtube.leanback.v4").
     * - PRIME_VIDEO: "amazon" é o appId por trás da tecla dedicada Amazon
     *   do Magic Remote (WebOS.Key_webOS_Amazon), documentado em notas
     *   públicas de remapeamento de teclas do webOS.
     * - PLEX: "cdp-30", relatado em discussão pública sobre remapeamento
     *   de teclas do webOS (fonte comunitária, não documentação oficial
     *   da Plex ou da LG).
     *
     * Apps SEM entrada aqui propositalmente (DISNEY_PLUS, MAX,
     * APPLE_TV_PLUS, PARAMOUNT_PLUS, CRUNCHYROLL, GLOBOPLAY): nenhuma
     * fonte confiável encontrada com o App ID desses apps especificamente
     * para webOS (os IDs de iOS/tvOS/Android são completamente diferentes
     * e não servem aqui). RemoteKey continua existindo no enum, mas
     * LgWebOsController.APP_LAUNCH_MAP não tem entrada - a UI trata como
     * "não suportado nesta TV" via TvController.supportedApps(), mesmo
     * padrão já usado pela Samsung para o Crunchyroll.
     */
    val NETFLIX_APP_ID = "netflix"
    val YOUTUBE_APP_ID = "youtube.leanback.v4"
    val PRIME_VIDEO_APP_ID = "amazon"
    val PLEX_APP_ID = "cdp-30"

    // ===== Identidade "signed" do manifest =====
    // Estes valores (appId, vendorId, nomes localizados, serial e a lista
    // de permissions abaixo em SIGNED_PERMISSIONS) NÃO podem ser
    // alterados livremente: MANIFEST_SIGNATURE foi calculada em cima
    // exatamente deste conteúdo. Mudar qualquer um destes campos invalida
    // a assinatura e a TV volta a rejeitar o registro silenciosamente
    // (mesmo sintoma do bug original: socket abre, popup nunca aparece).
    private const val SIGNED_APP_ID = "com.lge.test"
    private const val SIGNED_VENDOR_ID = "com.lge"
    private const val SIGNED_LOCALIZED_APP_NAME = "LG Remote App"
    private const val SIGNED_LOCALIZED_VENDOR_NAME = "LG Electronics"
    private const val SIGNED_SERIAL = "2f930e2d2cfe083771f68e4fe7bb07"
    private const val SIGNED_CREATED = "20140509"

    /**
     * Subconjunto de permissions que faz parte do bloco "signed" - ligado
     * à assinatura em [MANIFEST_SIGNATURE]. Diferente (menor) da lista
     * [PERMISSIONS] usada no nível superior do manifest, que essa sim
     * pode variar livremente (não é coberta pela assinatura).
     */
    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE",
        "CONTROL_INPUT_TEXT",
        "CONTROL_MOUSE_AND_KEYBOARD",
        "READ_INSTALLED_APPS",
        "READ_LGE_SDX",
        "READ_NOTIFICATIONS",
        "SEARCH",
        "WRITE_SETTINGS",
        "WRITE_NOTIFICATION_ALERT",
        "CONTROL_POWER",
        "READ_CURRENT_CHANNEL",
        "READ_RUNNING_APPS",
        "READ_UPDATE_INFO",
        "UPDATE_FROM_REMOTE_APP",
        "READ_LGE_TV_INPUT_EVENTS",
        "READ_TV_CURRENT_TIME"
    )

    /**
     * Lista de permissões do nível superior do manifest (fora de
     * "signed"). Baseada no manifest de referência usado por
     * lgtv2/Home Assistant - libs testadas em campo contra firmwares
     * reais. Um manifest reduzido corre o risco de ser aceito pela TV mas
     * com permissões insuficientes silenciosamente (só descoberto na hora
     * de usar um comando específico), por isso optamos por reaproveitar a
     * lista completa já validada em vez de tentar adivinhar um
     * subconjunto mínimo. Esta lista NÃO é coberta pela assinatura, então
     * pode ganhar/perder itens sem invalidar [MANIFEST_SIGNATURE].
     *
     * CONTROL_MOUSE_AND_KEYBOARD e CONTROL_INPUT_TEXT foram adicionadas
     * nesta fase: testado em campo contra uma TV LM625B (webOS 4.x, linha
     * de entrada), a requisição SSAP
     * "ssap://com.webos.service.networkinput/getPointerInputSocket"
     * retornava {"type":"error","error":"401 insufficient permissions"}
     * mesmo com o pareamento aceito e client-key válido - apesar dessas
     * duas permissões já constarem em [SIGNED_PERMISSIONS] (dentro de
     * "signed", coberto pela assinatura fixa). Esse modelo/firmware
     * aparentemente também exige a permissão espelhada aqui, fora de
     * "signed", para autorizar o pointer socket - divergência não coberta
     * pelo manifest de referência das libs (lgtv2/Home Assistant), que
     * costuma funcionar sem isso na maioria dos modelos testados pela
     * comunidade. CONTROL_INPUT_TEXT entra junto porque o envio de texto
     * livre (sendText, ainda não implementado) vai depender do mesmo
     * pointer socket.
     *
     * IMPORTANTE: como o client-key salvo foi pareado sob o manifest
     * antigo (sem essas duas permissões), é necessário ESQUECER a TV
     * (forgetDevice) e parear de novo depois desta mudança - a TV emite
     * um client-key novo já refletindo o manifest atualizado; o antigo
     * continua "válido" para reconectar mas sem a permissão extra.
     */
    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
        "TEST_OPEN", "TEST_PROTECTED",
        "CONTROL_AUDIO", "CONTROL_DISPLAY",
        "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
        "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_INPUT_TEXT",
        "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
        "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST",
        "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO"
    )

    /**
     * Assinatura RSA fixa e pública, gerada contra um certificado de
     * teste ("test-signing-cert") ainda aceito pelo firmware webOS.
     * Mesmo valor usado por lgtv2, aiowebostv, bscpylgtv e pela
     * integração oficial webOS do Home Assistant - todos projetos
     * open-source, valor amplamente documentado. Só é válida em conjunto
     * com o conteúdo exato de "signed" (ver constantes SIGNED_* acima).
     */
    private const val MANIFEST_SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9hL1Iu4bkoxvsC7z70lMehul27ThbCT4mV4qXG8muNolFbjA1CxIJmiIWOycTr9nQrQ9uctFEcSDMBAEgOJnfZ98wviJRAdEnQOZm31VBaYS4mHT26aXtDmDaC1AmDkQiokUUPBjSKuiwqA5mkyKN0mSVIAyMBAApiEwe6f2waCUyzocmuqSJtOwFY9K6SqmSlxpqfacSb59vHUsK9CGP12KeR6JVwbY8OG3jFO7SUUcskGh8y1frxc9amG8/jVmT/rMQFDMxx31Sq3wt/oIsAAfWkAxnu6t2M2h2CTdXfaXQZP5ZWCsvpqOffb7EOKUXpvIcVKrq2GSc6mvBVN9YW/T5FZ9OrCEcqCudMTNz3g=="

    /** Resultado da interpretação de uma mensagem recebida da TV. */
    sealed class LgEvent {
        /**
         * TV aceitou o pareamento (usuário confirmou o popup, ou o
         * client-key salvo ainda era válido). [clientKey] deve ser salvo e
         * reenviado em toda reconexão futura - equivalente ao "token" da
         * Samsung.
         */
        data class Registered(val clientKey: String) : LgEvent()

        /** TV está exibindo o popup de autorização, aguardando confirmação do usuário. */
        object PairingRequired : LgEvent()

        /**
         * Resposta ao pedido de [URI_GET_POINTER_INPUT_SOCKET]: a TV
         * informou o endereço (já uma URL completa "ws://..."/"wss://...")
         * do socket dedicado a botões.
         */
        data class PointerSocketReady(val socketPath: String) : LgEvent()

        /** Falha reportada pela própria TV para uma requisição SSAP qualquer. */
        data class Error(val message: String) : LgEvent()

        /** Mensagem reconhecida pelo JSON, mas sem tratamento específico nesta fase. */
        data class Unknown(val type: String?) : LgEvent()
    }

    /**
     * Monta a mensagem de registro/pareamento. Sem [clientKey] (null ou
     * vazio): primeiro pareamento, a TV mostra o popup de autorização. Com
     * [clientKey]: tenta reconectar direto, sem popup (mesma ideia do
     * "token" da Samsung em buildSocketUrl).
     *
     * IMPORTANTE: o manifest aqui montado inclui o bloco "signatures" -
     * ver documentação no topo do arquivo. Não remover nem alterar o
     * conteúdo de "signed" sem também atualizar [MANIFEST_SIGNATURE] (o
     * que exigiria uma chave privada real da LG, que não temos).
     */
    fun buildRegisterMessage(clientKey: String?): String {
        val signed = JSONObject().apply {
            put("created", SIGNED_CREATED)
            put("appId", SIGNED_APP_ID)
            put("vendorId", SIGNED_VENDOR_ID)
            put("localizedAppNames", JSONObject().put("", SIGNED_LOCALIZED_APP_NAME))
            put("localizedVendorNames", JSONObject().put("", SIGNED_LOCALIZED_VENDOR_NAME))
            put("permissions", JSONArray(SIGNED_PERMISSIONS))
            put("serial", SIGNED_SERIAL)
        }

        val signatures = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("signatureVersion", 1)
                    put("signature", MANIFEST_SIGNATURE)
                }
            )
        }

        val manifest = JSONObject().apply {
            put("manifestVersion", 1)
            put("appVersion", "1.1")
            put("signed", signed)
            put("permissions", JSONArray(PERMISSIONS))
            put("signatures", signatures)
        }

        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (!clientKey.isNullOrBlank()) put("client-key", clientKey)
            put("manifest", manifest)
        }

        return JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            put("payload", payload)
        }.toString()
    }

    /** Monta uma requisição SSAP genérica no formato {type, id, uri, payload}. */
    fun buildRequestMessage(id: String, uri: String, payload: JSONObject = JSONObject()): String {
        return JSONObject().apply {
            put("type", "request")
            put("id", id)
            put("uri", uri)
            put("payload", payload)
        }.toString()
    }

    /**
     * Monta o comando de botão para o pointer input socket. Formato oficial
     * (texto plano, não JSON): "type:button\nname:<BOTAO>\n\n". Confirmado
     * de forma consistente entre múltiplas libs independentes (lgtv2,
     * webos-lib) e pelo binding webOS do Home Assistant, que documenta os
     * nomes de botão conhecidos (HOME, UP, DOWN, ENTER, MUTE, VOLUMEUP etc).
     */
    fun buildButtonCommand(buttonName: String): String =
        "type:button\nname:$buttonName\n\n"

    /**
     * Interpreta uma mensagem de texto (JSON) recebida do WebSocket
     * PRINCIPAL da TV. O pointer socket não manda respostas relevantes
     * nesta fase, então esta função só é usada para o socket principal.
     */
    fun parseEvent(raw: String): LgEvent {
        return try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "registered" -> {
                    val clientKey = json.optJSONObject("payload")
                        ?.optString("client-key")
                        ?.takeIf { it.isNotBlank() }
                    if (clientKey != null) LgEvent.Registered(clientKey)
                    else LgEvent.Error("Resposta 'registered' sem client-key")
                }

                "response" -> {
                    val payload = json.optJSONObject("payload")
                    val socketPath = payload?.optString("socketPath")?.takeIf { it.isNotBlank() }
                    val pairingType = payload?.optString("pairingType")
                    when {
                        socketPath != null -> LgEvent.PointerSocketReady(socketPath)
                        pairingType == "PROMPT" -> LgEvent.PairingRequired
                        else -> LgEvent.Unknown("response")
                    }
                }

                "error" -> LgEvent.Error(json.optString("error", "Erro desconhecido no protocolo LG"))

                else -> LgEvent.Unknown(json.optString("type").takeIf { it.isNotBlank() })
            }
        } catch (e: Exception) {
            LgEvent.Unknown(null)
        }
    }
}
