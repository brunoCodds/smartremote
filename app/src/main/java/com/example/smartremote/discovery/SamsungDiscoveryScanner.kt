package com.example.smartremote.discovery

import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.Constants
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scanner de CONFIRMAÇÃO (não de descoberta "do zero" como SSDP/mDNS): dado
 * um conjunto de IPs candidatos já vistos nesta rodada de busca por outros
 * scanners (ver [DiscoveryCache.candidateIps] / [DeviceScanner]), consulta
 * diretamente a API HTTP nativa que TVs Samsung 2016+ expõem em
 * `http://<ip>:8001/api/v2/` - texto puro, sem autenticação, devolvendo
 * nome amigável, modelo, UUID e MAC da TV em JSON. Formato observado (campos
 * relevantes; a API não é documentada oficialmente pela Samsung, mas é
 * usada de forma estável por integrações de terceiros amplamente adotadas
 * como o binding Samsung TV do openHAB e a biblioteca samsungtvws):
 *
 * ```
 * {
 *   "device": {
 *     "OS": "Tizen",
 *     "duid": "uuid:c15fc058-0ab1-4c8d-80ca-b3f11d81e291",
 *     "modelName": "QE65Q9FNA",
 *     "name": "[TV] Samsung Q9 Series (65)",
 *     "networkType": "wired",
 *     "wifiMac": "c0:48:e6:c3:3b:8d"
 *   },
 *   "id": "uuid:c15fc058-0ab1-4c8d-80ca-b3f11d81e291"
 * }
 * ```
 *
 * É exatamente este caminho que resolve o caso originalmente relatado (TV
 * Samsung que um app comercial encontra e o Smart Remote não): esta API
 * funciona MESMO QUANDO o SSDP falha por completo para aquele IP (firmware
 * que não responde a `ssdp:all`, rede com multicast mal configurado,
 * timing) - desde que o IP da TV tenha sido visto por QUALQUER outro
 * caminho (SSDP parcial, mDNS, ou futuramente uma varredura de sub-rede),
 * esta confirmação ainda consegue identificá-la de verdade.
 *
 * Não sabe nada sobre SSDP, mDNS ou qualquer outro protocolo - só recebe
 * uma lista de IPs e decide, por conta própria, se cada um responde como
 * uma TV Samsung. O `duid`/`id` retornado já vem no mesmo formato
 * "uuid:<uuid>" usado pelo USN do SSDP, então uma TV vista pelos dois
 * caminhos é automaticamente reconhecida como a mesma pelo
 * [DiscoveryAggregator] (via [DeviceIdentity], tier de UUID) - nenhuma
 * lógica extra de correlação foi necessária para isso.
 */
class SamsungDiscoveryScanner : DiscoveryScanner {

    override val name: String = "Samsung"

    @Volatile private var stopRequested = false

    /**
     * Implementação "vazia" da interface padrão [DiscoveryScanner] - este
     * scanner não varre a rede sozinho, ele só CONFIRMA candidatos (ver
     * [scanCandidates], com uma assinatura própria, chamada pelo
     * `DeviceScanner` depois que os scanners primários já terminaram).
     * Mantido implementando [DiscoveryScanner] mesmo assim para que, no
     * futuro, também possa ser tratado como qualquer outro scanner por
     * código genérico que só precise de `stop()`/`name` - hoje simplesmente
     * termina sem encontrar nada por conta própria.
     */
    override fun scan(
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        onFinished()
    }

    override fun stop() {
        stopRequested = true
    }

    /**
     * Confirma cada IP de [candidateIps] contra a API Samsung, em paralelo
     * (thread pool pequeno, já que cada tentativa individual tem timeout
     * curto mas o número de candidatos pode ser grande). Síncrona/
     * bloqueante do ponto de vista de quem chama - deve rodar em
     * background, igual aos demais scanners.
     */
    fun scanCandidates(
        candidateIps: Set<String>,
        onDeviceFound: (TvDevice) -> Unit,
        onFinished: () -> Unit
    ) {
        stopRequested = false
        DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_STARTED, "candidatos=${candidateIps.size}")

        if (candidateIps.isEmpty()) {
            DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_FINISHED, "nenhum candidato para confirmar")
            onFinished()
            return
        }

        val pool = Executors.newFixedThreadPool(minOf(candidateIps.size, 8))
        val latch = CountDownLatch(candidateIps.size)
        val perAttemptBudgetMs =
            (Constants.SAMSUNG_DISCOVERY_CONNECT_TIMEOUT_MS + Constants.SAMSUNG_DISCOVERY_READ_TIMEOUT_MS).toLong()

        candidateIps.forEach { ip ->
            pool.execute {
                try {
                    if (!stopRequested) {
                        confirm(ip)?.let(onDeviceFound)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Orçamento total generoso o bastante pra cobrir todos os
        // candidatos mesmo que o pool não consiga rodar todos em paralelo
        // de verdade (ex: mais candidatos do que threads no pool).
        val totalBudgetMs = perAttemptBudgetMs * candidateIps.size + 2000
        latch.await(totalBudgetMs, TimeUnit.MILLISECONDS)
        pool.shutdownNow()

        DiscoveryDiagnostics.log(name, DiscoveryEventType.SCAN_FINISHED, "confirmação concluída")
        onFinished()
    }

    private fun confirm(ip: String): TvDevice? {
        val location = "http://$ip:${Constants.SAMSUNG_DISCOVERY_HTTP_PORT}${Constants.SAMSUNG_DISCOVERY_API_PATH}"
        DiscoveryDiagnostics.log(name, DiscoveryEventType.REQUEST_SENT, "GET $location")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(location).openConnection() as HttpURLConnection).apply {
                connectTimeout = Constants.SAMSUNG_DISCOVERY_CONNECT_TIMEOUT_MS
                readTimeout = Constants.SAMSUNG_DISCOVERY_READ_TIMEOUT_MS
                requestMethod = "GET"
            }

            val responseCode = connection.responseCode
            DiscoveryDiagnostics.log(name, DiscoveryEventType.RESPONSE_RECEIVED, "$location -> HTTP $responseCode")

            if (responseCode !in 200..299) {
                DiscoveryDiagnostics.log(
                    name, DiscoveryEventType.DEVICE_DISCARDED,
                    "$ip: HTTP $responseCode (provavelmente não é uma TV Samsung nesta porta)"
                )
                return null
            }

            val body = connection.inputStream.bufferedReader().readText()
            DiscoveryDiagnostics.log(name, DiscoveryEventType.PAYLOAD_RECEIVED, "$ip (${body.length} chars)")

            val device = parseDeviceInfo(ip, body)
            if (device == null) {
                DiscoveryDiagnostics.log(
                    name, DiscoveryEventType.DEVICE_DISCARDED,
                    "$ip: corpo HTTP 2xx mas não reconhecido como device-info Samsung"
                )
            } else {
                DiscoveryDiagnostics.log(name, DiscoveryEventType.PAYLOAD_PARSED, "$ip: JSON reconhecido")
                DiscoveryDiagnostics.log(
                    name, DiscoveryEventType.DEVICE_CREATED,
                    "$ip -> nome=\"${device.name}\" modelo=${device.model} mac=${device.mac}"
                )
                DiscoveryDiagnostics.log(name, DiscoveryEventType.DEVICE_FORWARDED, "$ip encaminhado ao DiscoveryAggregator")
            }
            device
        } catch (e: Exception) {
            // Timeout, connection refused, host inalcançável, etc - é o
            // resultado ESPERADO pra a maior parte dos IPs candidatos (a
            // maioria não vai ser uma Samsung). Não é erro de scan, é
            // "não confirmado" - por isso vira DEVICE_DISCARDED, não
            // SCAN_ERROR (que sinalizaria falha do scanner como um todo).
            DiscoveryDiagnostics.log(
                name, DiscoveryEventType.DEVICE_DISCARDED,
                "$ip: ${e::class.simpleName} - ${e.message}"
            )
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extrai os campos relevantes do JSON devolvido pela API. Retorna null
     * se o formato não bater com o esperado (nesse IP/porta responde algo
     * que não é uma TV Samsung - ex: outro serviço HTTP qualquer).
     */
    private fun parseDeviceInfo(ip: String, body: String): TvDevice? {
        return try {
            val root = JSONObject(body)
            val device = root.optJSONObject("device") ?: return null

            val friendlyName = device.optString("name").takeIf { it.isNotBlank() } ?: return null
            val duid = device.optString("duid").takeIf { it.isNotBlank() }
                ?: root.optString("id").takeIf { it.isNotBlank() }
            val modelName = device.optString("modelName").takeIf { it.isNotBlank() }
            val mac = device.optString("wifiMac").takeIf { it.isNotBlank() }
            val osRaw = device.optString("OS")

            TvDevice(
                name = friendlyName,
                brand = "Samsung",
                model = modelName,
                ip = ip,
                port = Constants.SAMSUNG_DISCOVERY_HTTP_PORT,
                protocol = DeviceProtocol.SAMSUNG_HTTP,
                os = if (osRaw.equals("Tizen", ignoreCase = true)) TvOperatingSystem.TIZEN else TvOperatingSystem.UNKNOWN,
                deviceId = duid,
                connected = false,
                mac = mac
            )
        } catch (e: Exception) {
            null
        }
    }
}
