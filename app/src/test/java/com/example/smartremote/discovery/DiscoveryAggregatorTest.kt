package com.example.smartremote.discovery

import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testes de [DiscoveryAggregator] - dedup/merge/confidence de uma rodada
 * de busca (ver o KDoc da classe para as regras completas).
 */
class DiscoveryAggregatorTest {

    private lateinit var aggregator: DiscoveryAggregator

    @Before
    fun setUp() {
        aggregator = DiscoveryAggregator()
    }

    private fun device(
        name: String = "TV de teste",
        brand: String? = null,
        model: String? = null,
        os: TvOperatingSystem = TvOperatingSystem.UNKNOWN,
        deviceId: String? = null,
        mac: String? = null,
        ip: String = "192.168.0.40",
        protocol: DeviceProtocol = DeviceProtocol.SSDP
    ) = TvDevice(
        name = name,
        brand = brand,
        model = model,
        ip = ip,
        port = null,
        protocol = protocol,
        os = os,
        deviceId = deviceId,
        connected = false,
        mac = mac
    )

    // ===================== Dedup por cada critério =====================

    @Test
    fun `primeiro dispositivo oferecido e sempre New`() {
        val result = aggregator.offer(device())
        assertTrue(result is DiscoveryAggregator.Result.New)
    }

    @Test
    fun `dedup por uuid - mesmo uuid em deviceId de scanners diferentes e reconhecido como a mesma TV`() {
        val primeiro = device(deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", protocol = DeviceProtocol.SSDP, brand = "Samsung", model = "QN90A")
        val segundo = device(deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", protocol = DeviceProtocol.SAMSUNG_HTTP, brand = "Samsung", model = "QN90A", mac = "aa:bb:cc:dd:ee:ff", ip = "192.168.0.41")

        aggregator.offer(primeiro)
        val result = aggregator.offer(segundo)

        assertTrue(result is DiscoveryAggregator.Result.Upgraded)
        assertEquals(1, aggregator.snapshot().size)
    }

    @Test
    fun `dedup por deviceId bruto quando nao ha uuid reconhecivel`() {
        val primeiro = device(deviceId = "servico-mdns-abc", protocol = DeviceProtocol.MDNS)
        val segundo = device(deviceId = "servico-mdns-abc", protocol = DeviceProtocol.MDNS, brand = "LG", model = "OLED55", ip = "192.168.0.42")

        aggregator.offer(primeiro)
        aggregator.offer(segundo)

        assertEquals(1, aggregator.snapshot().size)
    }

    @Test
    fun `dedup por MAC quando nenhum identificador unico bate`() {
        val primeiro = device(deviceId = "usn-generico-1", mac = "aa:bb:cc:dd:ee:ff", ip = "192.168.0.43")
        val segundo = device(deviceId = "usn-generico-2", mac = "AA-BB-CC-DD-EE-FF", brand = "Samsung", model = "QN90A", ip = "192.168.0.44")

        aggregator.offer(primeiro)
        aggregator.offer(segundo)

        assertEquals(1, aggregator.snapshot().size)
    }

    @Test
    fun `dedup por nome mais modelo quando nao ha id nem mac em comum`() {
        val primeiro = device(brand = "Samsung", model = "QN90A", ip = "192.168.0.45")
        val segundo = device(brand = "Samsung", model = "QN90A", os = TvOperatingSystem.TIZEN, ip = "192.168.0.46")

        aggregator.offer(primeiro)
        aggregator.offer(segundo)

        assertEquals(1, aggregator.snapshot().size)
    }

    @Test
    fun `dedup por IP como ultimo recurso`() {
        val primeiro = device(ip = "192.168.0.47")
        val segundo = device(name = "Outro nome qualquer", ip = "192.168.0.47")

        aggregator.offer(primeiro)
        aggregator.offer(segundo)

        assertEquals(1, aggregator.snapshot().size)
    }

    @Test
    fun `dispositivos sem nenhuma chave em comum sao tratados como TVs diferentes`() {
        val primeiro = device(deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", ip = "192.168.0.50")
        val segundo = device(deviceId = "uuid:11111111-2222-3333-4444-555555555555", ip = "192.168.0.51")

        aggregator.offer(primeiro)
        val result = aggregator.offer(segundo)

        assertTrue(result is DiscoveryAggregator.Result.New)
        assertEquals(2, aggregator.snapshot().size)
    }

    // ===================== Merge / confidence / Ignored =====================

    @Test
    fun `resultado com confidence maior gera Upgraded e vira o registro atual`() {
        val generico = device(name = "Dispositivo SSDP", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val identificado = device(name = "Samsung TV Sala", brand = "Samsung", model = "QN90A", os = TvOperatingSystem.TIZEN, deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        aggregator.offer(generico)
        val result = aggregator.offer(identificado)

        assertTrue(result is DiscoveryAggregator.Result.Upgraded)
        val upgraded = (result as DiscoveryAggregator.Result.Upgraded).device
        assertEquals("Samsung", upgraded.brand)
        assertEquals("QN90A", upgraded.model)
    }

    @Test
    fun `resultado com confidence igual ou menor gera Ignored e nao substitui o registro`() {
        val identificado = device(brand = "Samsung", model = "QN90A", os = TvOperatingSystem.TIZEN, deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val maisFraco = device(name = "Dispositivo SSDP", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        aggregator.offer(identificado)
        val result = aggregator.offer(maisFraco)

        assertTrue(result is DiscoveryAggregator.Result.Ignored)
        // O snapshot continua com o dispositivo mais completo, não o fraco.
        assertEquals("Samsung", aggregator.snapshot().single().brand)
    }

    @Test
    fun `merge preserva campo que so o dispositivo base tinha`() {
        // base tem modelo (e um nome genérico, de propósito, só para que a
        // diferença de confidence fique clara), mas não tem MAC nem OS
        // reconhecido; richer tem confidence maior (nome real + OS + MAC)
        // mas não traz modelo nenhum.
        val base = device(name = "Dispositivo SSDP", brand = "Samsung", model = "QN90A", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val richer = device(name = "Samsung TV Sala", brand = "Samsung", model = null, os = TvOperatingSystem.TIZEN, mac = "aa:bb:cc:dd:ee:ff", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        aggregator.offer(base)
        val result = aggregator.offer(richer) as DiscoveryAggregator.Result.Upgraded

        // richer venceu (confidence maior), mas o modelo que só o base
        // tinha não pode ter se perdido no merge.
        assertEquals("QN90A", result.device.model)
        assertEquals(TvOperatingSystem.TIZEN, result.device.os)
        assertEquals("aa:bb:cc:dd:ee:ff", result.device.mac)
    }

    @Test
    fun `nome generico nao substitui um nome real ja visto mesmo com confidence maior`() {
        val comNomeReal = device(name = "Samsung TV Sala", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val maisCompletoMasGenerico = device(
            name = "Dispositivo SSDP",
            brand = "Samsung",
            model = "QN90A",
            os = TvOperatingSystem.TIZEN,
            deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        )

        aggregator.offer(comNomeReal)
        val result = aggregator.offer(maisCompletoMasGenerico) as DiscoveryAggregator.Result.Upgraded

        assertEquals("Samsung TV Sala", result.device.name)
    }

    @Test
    fun `Upgraded carrega a stableKey anterior para a UI poder substituir o item certo`() {
        val generico = device(name = "Dispositivo SSDP", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val identificado = device(brand = "Samsung", model = "QN90A", deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        aggregator.offer(generico)
        val result = aggregator.offer(identificado) as DiscoveryAggregator.Result.Upgraded

        assertEquals(generico.stableKey(), result.previousKey)
    }

    // ===================== reset =====================

    @Test
    fun `reset descarta todo o estado da rodada anterior`() {
        aggregator.offer(device(ip = "192.168.0.60"))
        aggregator.reset()

        assertEquals(0, aggregator.snapshot().size)

        val result = aggregator.offer(device(ip = "192.168.0.60"))
        assertTrue(result is DiscoveryAggregator.Result.New)
    }

    @Test
    fun `snapshot vazio antes de qualquer offer`() {
        assertEquals(0, aggregator.snapshot().size)
        assertNull(aggregator.snapshot().firstOrNull())
    }
}
