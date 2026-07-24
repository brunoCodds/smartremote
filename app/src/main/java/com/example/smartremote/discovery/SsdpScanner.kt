package com.example.smartremote.discovery

import android.util.Log
import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.Constants
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Descoberta via SSDP (UPnP): envia um M-SEARCH multicast e escuta as
 * respostas das TVs na rede. Quando possível, busca o XML de descrição do
 * dispositivo (LOCATION) para extrair fabricante e modelo reais.
 *
 * Chamada de forma síncrona/bloqueante - deve rodar em background thread.
 */
class SsdpScanner {

    companion object {
        // TAG e limite usados apenas pelos logs temporários de diagnóstico
        // desta etapa. Remover (ou reduzir a verbosidade) após a causa raiz
        // do problema "fabricante desconhecido" ser confirmada e corrigida.
        private const val DIAG_TAG = "SsdpDiagnostic"
        private const val DIAG_XML_SNIPPET_LENGTH = 500

        // Como o M-SEARCH usa ST: ssdp:all, uma única TV física responde
        // várias vezes - uma por serviço/dispositivo embutido que ela
        // anuncia (rootdevice, MediaRenderer, serviço do fabricante etc).
        // Cada resposta vem com um USN diferente, mas todos compartilham o
        // mesmo prefixo "uuid:<UUID>" - essa é a parte realmente estável
        // que identifica a TV, então é ela (e só ela) que vira o deviceId
        // usado como TvDevice.stableKey(). Sem isso, a mesma TV aparece
        // duplicada na lista de descoberta uma vez por serviço anunciado.
        private val USN_UUID_REGEX = Regex("uuid:[^:\\s]+")
    }

    fun scan(
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(Constants.SSDP_MULTICAST_ADDRESS)
            socket = MulticastSocket()
            socket.soTimeout = Constants.SSDP_SOCKET_TIMEOUT_MS

            val searchMessage = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: ${Constants.SSDP_MULTICAST_ADDRESS}:${Constants.SSDP_MULTICAST_PORT}\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 3\r\n")
                append("ST: ${Constants.SSDP_SEARCH_TARGET}\r\n")
                append("\r\n")
            }.toByteArray()

            val packet = DatagramPacket(
                searchMessage, searchMessage.size, group, Constants.SSDP_MULTICAST_PORT
            )
            socket.send(packet)

            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + Constants.SSDP_TOTAL_TIMEOUT_MS

            // Dedup local desta rodada de scan: evita buscar o XML de
            // descrição repetidamente para a mesma TV só porque ela
            // respondeu várias vezes ao ssdp:all (uma vez por serviço).
            val seenStableIds = mutableSetOf<String>()

            while (System.currentTimeMillis() < deadline) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    parseResponse(text, response.address?.hostAddress, seenStableIds)?.let(onDeviceFound)
                } catch (timeout: SocketTimeoutException) {
                    // ignora e continua tentando até o deadline geral
                }
            }
        } catch (e: Exception) {
            onError("Erro na descoberta SSDP: ${e.message}")
        } finally {
            socket?.close()
            onFinished()
        }
    }

    private fun parseResponse(raw: String, ip: String?, seenStableIds: MutableSet<String>): TvDevice? {
        if (ip == null) return null

        val headers = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.take(idx).trim().uppercase() to line.substring(idx + 1).trim()
            }
            .toMap()

        val location = headers["LOCATION"]
        Log.d(DIAG_TAG, "Resposta SSDP de $ip -> LOCATION=\"$location\" SERVER=\"${headers["SERVER"]}\"")

        if (location == null) {
            Log.w(DIAG_TAG, "Descartando resposta de $ip: header LOCATION ausente. Headers brutos: $headers")
            return null
        }

        val server = headers["SERVER"].orEmpty()
        val usn = headers["USN"]
        val stableId = extractStableDeviceId(usn) ?: usn

        // Mesma TV já processada nesta rodada (respondeu de novo para um
        // serviço/dispositivo embutido diferente) - ignora silenciosamente
        // em vez de buscar o XML e notificar onDeviceFound outra vez.
        if (stableId != null && !seenStableIds.add(stableId)) {
            Log.d(DIAG_TAG, "Ignorando resposta duplicada de $ip (mesma TV, stableId=$stableId)")
            return null
        }

        val details = fetchDeviceDescription(location)
        Log.d(
            DIAG_TAG,
            "Resultado do parse de descrição UPnP para $ip ($location): " +
                "friendlyName=${details?.friendlyName ?: "NULO"}, " +
                "manufacturer=${details?.manufacturer ?: "NULO"}, " +
                "modelName=${details?.modelName ?: "NULO"}, " +
                "deviceType=${details?.deviceType ?: "NULO"}"
        )

        val os = guessOs(
            manufacturer = details?.manufacturer,
            modelName = details?.modelName,
            deviceType = details?.deviceType,
            server = server
        )
        Log.d(DIAG_TAG, "OS decidido para $ip: $os (manufacturer=${details?.manufacturer}, modelName=${details?.modelName}, deviceType=${details?.deviceType}, server=\"$server\")")

        return TvDevice(
            name = details?.friendlyName ?: server.takeIf { it.isNotBlank() } ?: "Dispositivo SSDP",
            brand = details?.manufacturer,
            model = details?.modelName,
            ip = ip,
            port = runCatching { URL(location).port }.getOrNull()?.takeIf { it != -1 },
            protocol = DeviceProtocol.SSDP,
            os = os,
            deviceId = stableId,
            connected = false
        )
    }

    /**
     * Extrai apenas o "uuid:<UUID>" do USN, descartando o sufixo
     * "::urn:..." que varia conforme o serviço/dispositivo embutido
     * anunciado. Retorna null se o USN não tiver esse formato (fallback
     * para o USN bruto é feito por quem chama esta função).
     */
    private fun extractStableDeviceId(usn: String?): String? {
        if (usn.isNullOrBlank()) return null
        return USN_UUID_REGEX.find(usn)?.value
    }

    /** GET simples no LOCATION do UPnP para extrair friendlyName/manufacturer/modelName. */
    private fun fetchDeviceDescription(location: String): DeviceDescription? {
        Log.d(DIAG_TAG, "Iniciando GET da descrição UPnP: $location")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(location).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }

            val responseCode = connection.responseCode
            Log.d(DIAG_TAG, "GET $location -> HTTP $responseCode")

            if (responseCode !in 200..299) {
                Log.w(DIAG_TAG, "GET $location retornou código não-2xx ($responseCode); abortando parse deste dispositivo.")
                return null
            }

            val xml = connection.inputStream.bufferedReader().readText()
            Log.d(DIAG_TAG, "XML recebido de $location (${xml.length} chars). Trecho: ${xml.take(DIAG_XML_SNIPPET_LENGTH)}")

            val friendlyName = extractXmlTag(xml, "friendlyName")
            val manufacturer = extractXmlTag(xml, "manufacturer")
            val modelName = extractXmlTag(xml, "modelName")
            val deviceType = extractXmlTag(xml, "deviceType")

            if (friendlyName == null) Log.w(DIAG_TAG, "Tag <friendlyName> NÃO encontrada/vazia no XML de $location")
            if (manufacturer == null) Log.w(DIAG_TAG, "Tag <manufacturer> NÃO encontrada/vazia no XML de $location")
            if (modelName == null) Log.w(DIAG_TAG, "Tag <modelName> NÃO encontrada/vazia no XML de $location")
            if (deviceType == null) Log.w(DIAG_TAG, "Tag <deviceType> NÃO encontrada/vazia no XML de $location")

            if (friendlyName == null) {
                Log.w(DIAG_TAG, "Descartando descrição de $location: friendlyName é obrigatório e não foi encontrado.")
                return null
            }

            DeviceDescription(
                friendlyName = friendlyName,
                manufacturer = manufacturer,
                modelName = modelName,
                deviceType = deviceType
            )
        } catch (e: Exception) {
            // Diagnóstico: antes esta exceção era engolida silenciosamente
            // (catch retornando null sem log). Isso escondia a causa raiz
            // de "fabricante desconhecido" - por exemplo, bloqueio de
            // cleartext HTTP (UnknownServiceException) a partir do Android 9.
            Log.e(DIAG_TAG, "Falha ao obter/parsear descrição UPnP de $location", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        // Mantido propositalmente igual ao original nesta etapa (apenas
        // diagnóstico) - não corrigir ainda, mesmo que a suspeita seja de
        // que a ausência de RegexOption.DOT_MATCHES_ALL atrapalhe tags
        // "pretty-printed" com quebra de linha entre a abertura/fechamento.
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Heurística principal de detecção de SO: usa manufacturer/modelName/
     * deviceType (obtidos do XML de descrição UPnP), que são bem mais
     * confiáveis que o header SERVER - por exemplo, TVs Samsung reais
     * costumam anunciar "Samsung-Linux" no SERVER (sem a palavra "tizen"),
     * mas sempre trazem "Samsung Electronics" como manufacturer no XML.
     *
     * Se o XML não foi obtido (details == null, ex: TV não respondeu ou
     * cleartext bloqueado), cai no fallback por SERVER como antes.
     */
    private fun guessOs(
        manufacturer: String?,
        modelName: String?,
        deviceType: String?,
        server: String
    ): TvOperatingSystem {
        val combined = listOfNotNull(manufacturer, modelName, deviceType)
            .joinToString(" ")
            .lowercase()

        return when {
            combined.contains("samsung") || combined.contains("tizen") -> TvOperatingSystem.TIZEN
            combined.contains("lg electronics") || combined.contains("webos") -> TvOperatingSystem.WEBOS
            combined.contains("roku") -> TvOperatingSystem.ROKU_OS
            combined.contains("amazon") -> TvOperatingSystem.FIRE_OS
            combined.contains("hisense") || combined.contains("vidaa") -> TvOperatingSystem.VIDAA
            combined.contains("android") -> TvOperatingSystem.ANDROID_TV
            combined.contains("google") -> TvOperatingSystem.GOOGLE_TV
            combined.isNotBlank() -> TvOperatingSystem.UNKNOWN
            else -> guessOsFromServer(server) // XML indisponível - único sinal restante é o SERVER
        }
    }

    /** Fallback usado quando o XML de descrição não pôde ser obtido. */
    private fun guessOsFromServer(server: String): TvOperatingSystem {
        val s = server.lowercase()
        return when {
            s.contains("tizen") -> TvOperatingSystem.TIZEN
            s.contains("webos") -> TvOperatingSystem.WEBOS
            s.contains("roku") -> TvOperatingSystem.ROKU_OS
            s.contains("fireos") || s.contains("amazon") -> TvOperatingSystem.FIRE_OS
            s.contains("vidaa") -> TvOperatingSystem.VIDAA
            s.contains("android") -> TvOperatingSystem.ANDROID_TV
            else -> TvOperatingSystem.UNKNOWN
        }
    }

    private data class DeviceDescription(
        val friendlyName: String,
        val manufacturer: String?,
        val modelName: String?,
        // Campo adicionado apenas para fins de diagnóstico nesta etapa
        // (log de deviceType pedido para investigação). Ainda não é usado
        // em nenhuma lógica de negócio (TvDevice não o recebe).
        val deviceType: String? = null
    )
}
