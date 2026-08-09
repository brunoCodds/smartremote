package com.example.smartremote.controller.androidtv

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.example.smartremote.model.RemoteKey
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * *** NOVO - v0.9, item 3 (Android TV / Google TV) ***
 *
 * Implementa o protocolo "Android TV Remote Service v2" (o mesmo usado
 * pelo app oficial "Google TV"), inteiramente em cima de
 * `com.google.protobuf:protobuf-javalite` - SEM gerar código a partir de
 * arquivos `.proto` (sem plugin protoc/codegen no Gradle). Cada mensagem é
 * montada/lida campo a campo diretamente com [CodedOutputStream]/
 * [CodedInputStream] (as primitivas de baixo nível do próprio runtime
 * oficial do Google para varints/length-delimited/etc.) - é real Protobuf
 * (mesmo wire format, mesma lib oficial), só sem a etapa de geração de
 * classes `MessageLite` para cada tipo.
 *
 * ## Por que não usar o plugin protoc/codegen do Gradle
 * Geraria classes Kotlin/Java a partir de `.proto` incluídos no projeto,
 * o que exigiria baixar o binário `protoc` durante o build (mais uma
 * dependência de rede/toolchain) só para um único fabricante, numa versão
 * cujas convenções deliberadamente evitam qualquer etapa de build extra
 * (sem DI, sem geração de código). Como as mensagens deste protocolo são
 * poucas e pequenas, escrevê-las campo a campo com as primitivas do
 * próprio runtime oficial (que já reflete o wire format exato do
 * protobuf) é uma troca razoável: continua sendo Protobuf de verdade, sem
 * o custo de toolchain adicional.
 *
 * ## Fonte do esquema (nomes de campo/números)
 * Não existe documentação OFICIAL publicada do protocolo v2 pela Google
 * (diferente do protocolo v1, mais antigo). O esquema abaixo foi
 * confirmado cruzando: (1) os arquivos `.proto` do pacote Python
 * `androidtvremote2` (ativamente mantido, usado pela integração Android TV
 * do Home Assistant), e (2) uma engenharia reversa publicamente
 * documentada do app oficial "Google TV" (decompilação do
 * `PairingManager`/mensagens, incluindo bytes brutos de exemplo
 * capturados em uma sessão real de pareamento) - os números de campo e a
 * estrutura batem entre as duas fontes independentes.
 *
 * ## Framing (como as mensagens trafegam no socket TCP/TLS)
 * MUITO mais simples que HTTP/WebSocket: cada mensagem Protobuf é
 * precedida por exatamente 1 BYTE indicando o tamanho da mensagem que vem
 * a seguir (0-255 - suficiente aqui, já que nenhuma mensagem deste
 * protocolo passa perto disso). Não é um varint multi-byte de propósito
 * geral (como o resto do wire format do Protobuf) - é literalmente um
 * único byte de comprimento, confirmado contra a mesma engenharia reversa
 * citada acima e contra o comportamento observado do app oficial. Ver
 * [AndroidTvSocketClient] para onde esse framing é lido/escrito de fato.
 */
object AndroidTvRemoteProtocol {

    // ===================== PairingMessage (porta 6467) =====================

    private const val PROTOCOL_VERSION = 2

    // PairingMessage.Status
    private const val STATUS_OK = 200

    // PairingMessage.PairingEncoding.EncodingType
    private const val ENCODING_TYPE_HEXADECIMAL = 3

    // PairingMessage.RoleType
    private const val ROLE_TYPE_INPUT = 1

    /** Tamanho do código de pareamento exibido na TV - 6 dígitos hexadecimais (3 bytes). */
    const val PAIRING_CODE_SYMBOL_LENGTH = 6

    /** `PairingMessage{protocol_version=2, pairing_request=PairingRequest{service_name, client_name}}` - primeira mensagem enviada, na porta 6467. */
    fun buildPairingRequestMessage(serviceName: String, clientName: String): ByteArray {
        val pairingRequest = buildMessage { cos ->
            cos.writeString(1, serviceName)
            cos.writeString(2, clientName)
        }
        return buildMessage { cos ->
            cos.writeInt32(1, PROTOCOL_VERSION)
            cos.writeByteArray(10, pairingRequest)
        }
    }

    /**
     * `PairingMessage{protocol_version=2, pairing_option=PairingOption{...}}`.
     * Anunciamos suporte a UM único encoding (hexadecimal, 6 dígitos) tanto
     * como entrada quanto saída - é o único que o protocolo v2 realmente
     * usa na prática (o código de 6 dígitos hex exibido na TV), mas o
     * campo é `repeated` no protocolo real, então respeitamos o formato.
     */
    fun buildPairingOptionMessage(): ByteArray {
        val encoding = buildPairingEncoding()
        val pairingOption = buildMessage { cos ->
            cos.writeByteArray(1, encoding) // input_encodings (repeated, 1 entrada)
            cos.writeByteArray(2, encoding) // output_encodings (repeated, 1 entrada)
            cos.writeInt32(3, ROLE_TYPE_INPUT)
        }
        return buildMessage { cos ->
            cos.writeInt32(1, PROTOCOL_VERSION)
            cos.writeByteArray(20, pairingOption)
        }
    }

    /** `PairingMessage{protocol_version=2, pairing_configuration=PairingConfiguration{...}}`. */
    fun buildPairingConfigurationMessage(): ByteArray {
        val configuration = buildMessage { cos ->
            cos.writeByteArray(1, buildPairingEncoding())
            cos.writeInt32(2, ROLE_TYPE_INPUT)
        }
        return buildMessage { cos ->
            cos.writeInt32(1, PROTOCOL_VERSION)
            cos.writeByteArray(30, configuration)
        }
    }

    /**
     * `PairingMessage{pairing_secret=PairingSecret{secret}}` - última
     * mensagem do pareamento, com o hash SHA-256 calculado por
     * [computePairingSecret] (só é enviada depois de validar localmente
     * que o hash bate com o checksum do código digitado pelo usuário -
     * ver KDoc de [computePairingSecret]).
     */
    fun buildPairingSecretMessage(secret: ByteArray): ByteArray {
        val pairingSecret = buildMessage { cos -> cos.writeByteArray(1, secret) }
        return buildMessage { cos ->
            cos.writeInt32(1, PROTOCOL_VERSION)
            cos.writeByteArray(40, pairingSecret)
        }
    }

    private fun buildPairingEncoding(): ByteArray = buildMessage { cos ->
        cos.writeInt32(1, ENCODING_TYPE_HEXADECIMAL)
        cos.writeInt32(2, PAIRING_CODE_SYMBOL_LENGTH)
    }

    /** Etapa do pareamento que a TV confirmou (a TV respondeu preenchendo o campo correspondente da PairingMessage). */
    enum class PairingAckKind { REQUEST, OPTION, CONFIGURATION, SECRET }

    sealed class PairingEvent {
        data class Ack(val kind: PairingAckKind) : PairingEvent()
        /** A TV respondeu com um status diferente de STATUS_OK (ex: BAD_SECRET - código digitado errado). */
        data class Error(val status: Int) : PairingEvent()
        object Unknown : PairingEvent()
    }

    /**
     * Lê uma PairingMessage vinda da TV e diz qual etapa ela confirmou (ou
     * se veio um erro). Não precisamos do CONTEÚDO de cada ack (ex: o
     * `server_name` do PairingRequestAck) para o pareamento funcionar -
     * só de SABER que a etapa foi confirmada, para prosseguir para a
     * próxima mensagem da sequência.
     */
    fun parsePairingMessage(bytes: ByteArray): PairingEvent {
        val input = CodedInputStream.newInstance(bytes)
        var status: Int? = null
        var ackKind: PairingAckKind? = null

        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                2 -> status = input.readInt32()
                11 -> { input.readByteArray(); ackKind = PairingAckKind.REQUEST }
                20 -> { input.readByteArray(); ackKind = PairingAckKind.OPTION }
                31 -> { input.readByteArray(); ackKind = PairingAckKind.CONFIGURATION }
                41 -> { input.readByteArray(); ackKind = PairingAckKind.SECRET }
                else -> input.skipField(tag)
            }
        }

        return when {
            status != null && status != STATUS_OK -> PairingEvent.Error(status)
            ackKind != null -> PairingEvent.Ack(ackKind)
            else -> PairingEvent.Unknown
        }
    }

    // ===================== Verificação do código exibido na TV =====================

    sealed class PairingSecretResult {
        data class Valid(val secret: ByteArray) : PairingSecretResult()
        object InvalidCode : PairingSecretResult()
        object UnsupportedKeyType : PairingSecretResult()
    }

    /**
     * *** O coração do pareamento Android TV - capriche no KDoc, avisado
     * no pedido da v0.9, porque não é nada óbvio. ***
     *
     * Quando a sessão de pareamento chega na etapa PairingConfigurationAck
     * (ver [PairingAckKind.CONFIGURATION]), a TV passa a exibir um código
     * de 6 dígitos HEXADECIMAIS na tela (3 bytes). O app precisa PROVAR
     * que "está vendo" esse código - não confiando cegamente em quem
     * abriu a conexão TLS - antes de considerar o pareamento válido.
     *
     * O código sozinho não é enviado de volta à TV (ela não teria como
     * conferir isso contra nada). Em vez disso, o algoritmo é:
     *
     * 1. O código de 6 dígitos hex representa 3 BYTES: `[checksum, m1, m2]`.
     *    O PRIMEIRO byte é um checksum; os outros DOIS ("os 2 bytes do
     *    meio", como descrito no pedido da v0.9) são um nonce.
     * 2. Calcula-se `SHA-256(clientModulus || clientExponent ||
     *    serverModulus || serverExponent || nonce)`, onde
     *    "clientModulus/clientExponent" são o módulo e o expoente público
     *    da chave RSA do CERTIFICADO DO PRÓPRIO APP (ver
     *    [AndroidTvKeystoreManager]), e "serverModulus/serverExponent" são
     *    os mesmos campos do certificado que A TV apresentou durante o
     *    handshake TLS na porta 6467 - cada valor é a representação em
     *    bytes SEM SINAL (big-endian, sem o eventual byte 0x00 extra que
     *    `BigInteger.toByteArray()` do Java adiciona para representações
     *    two's-complement - ver [unsignedBytes]).
     * 3. Se o PRIMEIRO byte do hash resultante bater com o byte de
     *    checksum do código (o primeiro dos 3 bytes originais), o código
     *    "bate" - ou seja, o app realmente está falando com a MESMA TV
     *    (mesmo par de certificados envolvidos) que está exibindo aquele
     *    código na tela, e o usuário realmente está vendo/digitando o
     *    código certo (não é um ataque onde outro dispositivo tentasse se
     *    passar pela TV, ou o usuário digitasse um código antigo/errado).
     * 4. Só então o HASH INTEIRO (32 bytes) é enviado de volta à TV como
     *    [buildPairingSecretMessage] - é essa prova, e não o código em si,
     *    que autentica o pareamento.
     *
     * Por que RSA (não EC) - ver KDoc de [AndroidTvKeystoreManager]: este
     * algoritmo especificamente opera sobre módulo/expoente de chave RSA,
     * então a escolha do algoritmo de chave não é livre.
     *
     * @param userEnteredCode os 6 dígitos hexadecimais que o usuário
     * digitou no app (o que ele está vendo na tela da TV agora).
     */
    fun computePairingSecret(
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate,
        userEnteredCode: String
    ): PairingSecretResult {
        val clientKey = clientCertificate.publicKey as? RSAPublicKey ?: return PairingSecretResult.UnsupportedKeyType
        val serverKey = serverCertificate.publicKey as? RSAPublicKey ?: return PairingSecretResult.UnsupportedKeyType

        val codeBytes = hexToBytes(userEnteredCode) ?: return PairingSecretResult.InvalidCode
        if (codeBytes.size != 3) return PairingSecretResult.InvalidCode
        val checksumByte = codeBytes[0]
        val nonce = codeBytes.copyOfRange(1, 3) // "os 2 bytes do meio"

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedBytes(clientKey.modulus))
        digest.update(unsignedBytes(clientKey.publicExponent))
        digest.update(unsignedBytes(serverKey.modulus))
        digest.update(unsignedBytes(serverKey.publicExponent))
        digest.update(nonce)
        val hash = digest.digest()

        return if (hash.isNotEmpty() && hash[0] == checksumByte) {
            PairingSecretResult.Valid(hash)
        } else {
            PairingSecretResult.InvalidCode
        }
    }

    /**
     * `BigInteger.toByteArray()` do Java retorna a representação
     * two's-complement mínima - para um número POSITIVO cujo byte mais
     * significativo já tem o bit alto ligado (comum em módulos RSA, que
     * são grandes), o Java antepõe um byte 0x00 extra só para deixar
     * claro que o número é positivo. O algoritmo do pareamento espera a
     * representação SEM SINAL "crua" (sem esse 0x00 extra) - por isso
     * removemos explicitamente, em vez de usar os bytes como vêm.
     */
    private fun unsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length != PAIRING_CODE_SYMBOL_LENGTH || clean.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) + lo).toByte()
        }
    }

    // ===================== RemoteMessage (porta 6466, sessão já pareada) =====================

    /** Ver [com.example.smartremote.model.RemoteKey] - equivalentes do Android (`android.view.KeyEvent`). */
    object KeyCodes {
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val DPAD_CENTER = 23
        const val BACK = 4
        const val HOME = 3
        const val ENTER = 66
        const val POWER = 26
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val VOLUME_MUTE = 164
        const val CHANNEL_UP = 166
        const val CHANNEL_DOWN = 167
        const val MEDIA_PLAY_PAUSE = 85
        const val MEDIA_PLAY = 126
        const val MEDIA_PAUSE = 127
        const val MEDIA_STOP = 86
        const val NUM_0 = 7
        const val NUM_1 = 8
        const val NUM_2 = 9
        const val NUM_3 = 10
        const val NUM_4 = 11
        const val NUM_5 = 12
        const val NUM_6 = 13
        const val NUM_7 = 14
        const val NUM_8 = 15
        const val NUM_9 = 16
        const val PROG_RED = 183
        const val PROG_GREEN = 184
        const val PROG_YELLOW = 185
        const val PROG_BLUE = 186
    }

    /** RemoteMessage.RemoteDirection. */
    const val DIRECTION_SHORT = 3

    /**
     * Tradução RemoteKey -> keycode Android - mapa único e compartilhado
     * (diferente de Samsung/LG, aqui o "código do botão" já É o keycode
     * nativo do Android, então não precisa de tabela por-controller).
     */
    val REMOTE_KEY_TO_KEYCODE: Map<RemoteKey, Int> = mapOf(
        RemoteKey.UP to KeyCodes.DPAD_UP,
        RemoteKey.DOWN to KeyCodes.DPAD_DOWN,
        RemoteKey.LEFT to KeyCodes.DPAD_LEFT,
        RemoteKey.RIGHT to KeyCodes.DPAD_RIGHT,
        RemoteKey.OK to KeyCodes.DPAD_CENTER,
        RemoteKey.BACK to KeyCodes.BACK,
        RemoteKey.HOME to KeyCodes.HOME,
        RemoteKey.POWER to KeyCodes.POWER,
        RemoteKey.MUTE to KeyCodes.VOLUME_MUTE,
        RemoteKey.VOLUME_UP to KeyCodes.VOLUME_UP,
        RemoteKey.VOLUME_DOWN to KeyCodes.VOLUME_DOWN,
        RemoteKey.CHANNEL_UP to KeyCodes.CHANNEL_UP,
        RemoteKey.CHANNEL_DOWN to KeyCodes.CHANNEL_DOWN,
        RemoteKey.PLAY_PAUSE to KeyCodes.MEDIA_PLAY_PAUSE,
        RemoteKey.PLAY to KeyCodes.MEDIA_PLAY,
        RemoteKey.PAUSE to KeyCodes.MEDIA_PAUSE,
        RemoteKey.STOP to KeyCodes.MEDIA_STOP,
        RemoteKey.NUM_0 to KeyCodes.NUM_0,
        RemoteKey.NUM_1 to KeyCodes.NUM_1,
        RemoteKey.NUM_2 to KeyCodes.NUM_2,
        RemoteKey.NUM_3 to KeyCodes.NUM_3,
        RemoteKey.NUM_4 to KeyCodes.NUM_4,
        RemoteKey.NUM_5 to KeyCodes.NUM_5,
        RemoteKey.NUM_6 to KeyCodes.NUM_6,
        RemoteKey.NUM_7 to KeyCodes.NUM_7,
        RemoteKey.NUM_8 to KeyCodes.NUM_8,
        RemoteKey.NUM_9 to KeyCodes.NUM_9,
        RemoteKey.RED to KeyCodes.PROG_RED,
        RemoteKey.GREEN to KeyCodes.PROG_GREEN,
        RemoteKey.YELLOW to KeyCodes.PROG_YELLOW,
        RemoteKey.BLUE to KeyCodes.PROG_BLUE
    )

    /**
     * Deep links conhecidos por app - mecanismo de "abrir app" TOTALMENTE
     * diferente de LG (App ID numérico)/Samsung (App ID alfanumérico): o
     * Android TV abre apps através de uma URL comum (ACTION_VIEW,
     * resolvida pelo Android via App Links/intent-filter do próprio app
     * instalado na TV) - o mesmo mecanismo usado pelo app oficial "Google
     * TV" e documentado pela comunidade como forma padrão de mapear "app
     * -> link" neste protocolo. Cada URL abaixo é o link canônico anunciado
     * publicamente pelo próprio serviço de streaming (página inicial do
     * app na web) - convergem com o que a comunidade documenta como
     * funcional para abrir cada app instalado.
     *
     * RemoteKey.GLOBOPLAY entra aqui com confiança menor: o mecanismo de
     * deep link em si é genérico (funciona para qualquer app que declare
     * suporte a esse link), mas não há confirmação de terceiros
     * especificamente para o Globoplay no Android TV (diferente de
     * Disney+/Max/Apple TV/Paramount+, todos amplamente documentados).
     * Mantido mesmo assim porque o pior caso (app não abre) é idêntico ao
     * de um ID não encontrado na Samsung/LG - sem risco de comportamento
     * incorreto, só de não funcionar nesse app específico.
     */
    val APP_DEEP_LINKS: Map<RemoteKey, String> = mapOf(
        RemoteKey.NETFLIX to "https://www.netflix.com/",
        RemoteKey.PRIME_VIDEO to "https://app.primevideo.com/",
        RemoteKey.YOUTUBE to "https://www.youtube.com/",
        RemoteKey.DISNEY_PLUS to "https://www.disneyplus.com/",
        RemoteKey.MAX to "https://play.max.com/",
        RemoteKey.APPLE_TV_PLUS to "https://tv.apple.com/",
        RemoteKey.PARAMOUNT_PLUS to "https://www.paramountplus.com/",
        RemoteKey.PLEX to "https://app.plex.tv/",
        RemoteKey.CRUNCHYROLL to "https://www.crunchyroll.com/",
        RemoteKey.GLOBOPLAY to "https://globoplay.globo.com/"
    )

    /** `RemoteMessage{remote_configure=RemoteConfigure{...}}` - primeira mensagem enviada na sessão já pareada (porta 6466). */
    fun buildRemoteConfigureMessage(): ByteArray {
        val deviceInfo = buildMessage { cos ->
            cos.writeString(1, "SmartRemote") // model
            cos.writeString(2, "SmartRemote") // vendor
            cos.writeInt32(3, 1)              // unknown1 - valor fixo observado em implementações de referência, sem significado documentado
            cos.writeString(4, "1")           // unknown2
            cos.writeString(5, "com.example.smartremote") // package_name
            cos.writeString(6, "1")           // app_version
        }
        val configure = buildMessage { cos ->
            cos.writeInt32(1, 622) // code1 - valor observado funcionando em implementações de referência ativamente mantidas; significado exato não documentado publicamente
            cos.writeByteArray(2, deviceInfo)
        }
        return buildMessage { cos -> cos.writeByteArray(1, configure) }
    }

    /** `RemoteMessage{remote_set_active=RemoteSetActive{active=1}}` - anuncia que este cliente é a sessão de controle ativa. */
    fun buildRemoteSetActiveMessage(active: Int = 1): ByteArray {
        val setActive = buildMessage { cos -> cos.writeInt32(1, active) }
        return buildMessage { cos -> cos.writeByteArray(2, setActive) }
    }

    /** `RemoteMessage{remote_ping_response=RemotePingResponse{val1}}` - resposta obrigatória ao ping periódico da TV (keepalive da sessão). */
    fun buildRemotePingResponseMessage(val1: Int): ByteArray {
        val pingResponse = buildMessage { cos -> cos.writeInt32(1, val1) }
        return buildMessage { cos -> cos.writeByteArray(9, pingResponse) }
    }

    /** `RemoteMessage{remote_key_inject=RemoteKeyInject{key_code, direction}}` - um toque de tecla (pressionar+soltar, ver [DIRECTION_SHORT]). */
    fun buildRemoteKeyInjectMessage(keyCode: Int, direction: Int = DIRECTION_SHORT): ByteArray {
        val keyInject = buildMessage { cos ->
            cos.writeInt32(1, keyCode)
            cos.writeInt32(2, direction)
        }
        return buildMessage { cos -> cos.writeByteArray(10, keyInject) }
    }

    /** `RemoteMessage{remote_app_link_launch_request=RemoteAppLinkLaunchRequest{app_link}}` - abre um app pelo deep link (ver [APP_DEEP_LINKS]). */
    fun buildRemoteAppLinkLaunchMessage(deepLink: String): ByteArray {
        val launch = buildMessage { cos -> cos.writeString(1, deepLink) }
        return buildMessage { cos -> cos.writeByteArray(90, launch) }
    }

    sealed class RemoteEvent {
        /** A TV está pedindo um "pong" - precisa ser respondido com [buildRemotePingResponseMessage] usando o mesmo val1, ou a TV derruba a sessão por inatividade. */
        data class PingRequest(val val1: Int) : RemoteEvent()
        /** A TV ecoou de volta o RemoteConfigure - alguns firmwares só consideram a sessão pronta depois disso. */
        object Configured : RemoteEvent()
        object Unknown : RemoteEvent()
    }

    fun parseRemoteMessage(bytes: ByteArray): RemoteEvent {
        val input = CodedInputStream.newInstance(bytes)
        var result: RemoteEvent = RemoteEvent.Unknown

        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> {
                    input.readByteArray()
                    result = RemoteEvent.Configured
                }
                8 -> {
                    val body = input.readByteArray()
                    result = RemoteEvent.PingRequest(readInt32Field(body, fieldNumber = 1))
                }
                else -> input.skipField(tag)
            }
        }
        return result
    }

    private fun readInt32Field(bytes: ByteArray, fieldNumber: Int): Int {
        val input = CodedInputStream.newInstance(bytes)
        var value = 0
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            if ((tag ushr 3) == fieldNumber) value = input.readInt32() else input.skipField(tag)
        }
        return value
    }

    // ===================== Helper de baixo nível =====================

    private inline fun buildMessage(block: (CodedOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        val cos = CodedOutputStream.newInstance(baos)
        block(cos)
        cos.flush()
        return baos.toByteArray()
    }
}
