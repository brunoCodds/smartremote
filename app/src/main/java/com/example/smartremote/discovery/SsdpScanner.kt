package com.example.smartremote.discovery

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

            while (System.currentTimeMillis() < deadline) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    parseResponse(text, response.address?.hostAddress)?.let(onDeviceFound)
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

    private fun parseResponse(raw: String, ip: String?): TvDevice? {
        if (ip == null) return null

        val headers = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.take(idx).trim().uppercase() to line.substring(idx + 1).trim()
            }
            .toMap()

        val location = headers["LOCATION"] ?: return null
        val server = headers["SERVER"].orEmpty()
        val usn = headers["USN"]

        val details = fetchDeviceDescription(location)

        return TvDevice(
            name = details?.friendlyName ?: server.takeIf { it.isNotBlank() } ?: "Dispositivo SSDP",
            brand = details?.manufacturer,
            model = details?.modelName,
            ip = ip,
            port = runCatching { URL(location).port }.getOrNull()?.takeIf { it != -1 },
            protocol = DeviceProtocol.SSDP,
            os = guessOsFromServer(server),
            deviceId = usn,
            connected = false
        )
    }

    /** GET simples no LOCATION do UPnP para extrair friendlyName/manufacturer/modelName. */
    private fun fetchDeviceDescription(location: String): DeviceDescription? {
        return try {
            val connection = (URL(location).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }
            val xml = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val name = extractXmlTag(xml, "friendlyName") ?: return null
            DeviceDescription(
                friendlyName = name,
                manufacturer = extractXmlTag(xml, "manufacturer"),
                modelName = extractXmlTag(xml, "modelName")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

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
        val modelName: String?
    )
}
