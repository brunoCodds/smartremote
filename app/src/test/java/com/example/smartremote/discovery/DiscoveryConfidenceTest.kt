package com.example.smartremote.discovery

import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [DiscoveryConfidence] - a pontuação de completude usada pelo
 * [DiscoveryAggregator] para decidir qual de dois resultados "vence"
 * quando são identificados como a mesma TV física.
 */
class DiscoveryConfidenceTest {

    private fun device(
        name: String = "TV de teste",
        brand: String? = null,
        model: String? = null,
        os: TvOperatingSystem = TvOperatingSystem.UNKNOWN,
        deviceId: String? = null,
        mac: String? = null
    ) = TvDevice(
        name = name,
        brand = brand,
        model = model,
        ip = "192.168.0.30",
        port = null,
        protocol = DeviceProtocol.SSDP,
        os = os,
        deviceId = deviceId,
        connected = false,
        mac = mac
    )

    @Test
    fun `dispositivo sem nenhuma informacao pontua zero`() {
        assertEquals(0, DiscoveryConfidence.score(device(name = "")))
    }

    @Test
    fun `pontuacao cresce a cada campo de identificacao preenchido`() {
        val soNome = device(name = "TV encontrada")
        val comMarca = device(name = "TV encontrada", brand = "Samsung")
        val comMarcaEModelo = device(name = "TV encontrada", brand = "Samsung", model = "QN90A")
        val completo = device(
            name = "TV encontrada",
            brand = "Samsung",
            model = "QN90A",
            os = TvOperatingSystem.TIZEN,
            deviceId = "uuid:abc",
            mac = "aa:bb:cc:dd:ee:ff"
        )

        val scoreSoNome = DiscoveryConfidence.score(soNome)
        val scoreComMarca = DiscoveryConfidence.score(comMarca)
        val scoreComMarcaEModelo = DiscoveryConfidence.score(comMarcaEModelo)
        val scoreCompleto = DiscoveryConfidence.score(completo)

        assertTrue(scoreComMarca > scoreSoNome)
        assertTrue(scoreComMarcaEModelo > scoreComMarca)
        assertTrue(scoreCompleto > scoreComMarcaEModelo)
    }

    @Test
    fun `nome generico dispositivo ssdp nao pontua como nome real`() {
        val comNomeGenerico = device(name = "Dispositivo SSDP")
        val comNomeReal = device(name = "Samsung TV Sala")
        // Nenhum dos dois tem marca/modelo/os/deviceId/mac - a única
        // diferença possível de pontuação vem do próprio nome.
        assertEquals(0, DiscoveryConfidence.score(comNomeGenerico))
        assertEquals(1, DiscoveryConfidence.score(comNomeReal))
    }

    @Test
    fun `isGenericName reconhece o marcador conhecido, variando caixa e espacos`() {
        assertTrue(DiscoveryConfidence.isGenericName("Dispositivo SSDP"))
        assertTrue(DiscoveryConfidence.isGenericName("  dispositivo ssdp  "))
        assertTrue(DiscoveryConfidence.isGenericName(""))
        assertFalse(DiscoveryConfidence.isGenericName("Samsung TV Sala"))
    }

    @Test
    fun `marca e modelo pesam mais que deviceId e mac isolados`() {
        val marcaModeloOs = device(brand = "LG", model = "OLED55", os = TvOperatingSystem.WEBOS)
        val soDeviceIdEMac = device(deviceId = "uuid:abc", mac = "aa:bb:cc:dd:ee:ff")
        assertTrue(DiscoveryConfidence.score(marcaModeloOs) > DiscoveryConfidence.score(soDeviceIdEMac))
    }
}
