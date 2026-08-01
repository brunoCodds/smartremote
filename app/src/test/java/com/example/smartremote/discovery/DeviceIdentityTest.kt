package com.example.smartremote.discovery

import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de [DeviceIdentity] - resolução de identidade usada SÓ durante a
 * agregação de uma rodada de busca (não confundir com [TvDevice.stableKey],
 * que tem seus próprios testes em TvDeviceStableKeyTest).
 */
class DeviceIdentityTest {

    // ===================== extractUuid =====================

    @Test
    fun `extractUuid encontra uuid dentro de string USN reduzida`() {
        val uuid = DeviceIdentity.extractUuid("uuid:12345678-90AB-CDEF-1234-567890ABCDEF")
        assertEquals("12345678-90ab-cdef-1234-567890abcdef", uuid)
    }

    @Test
    fun `extractUuid encontra uuid mesmo com sufixo depois dele`() {
        val uuid = DeviceIdentity.extractUuid("uuid:11111111-2222-3333-4444-555555555555::urn:dial-multiscreen-org:service:dial:1")
        assertEquals("11111111-2222-3333-4444-555555555555", uuid)
    }

    @Test
    fun `extractUuid retorna null quando nenhum candidato tem uuid reconhecivel`() {
        assertNull(DeviceIdentity.extractUuid("nome-do-servico-sem-uuid", "outra-string-qualquer"))
    }

    @Test
    fun `extractUuid retorna null para candidatos nulos ou em branco`() {
        assertNull(DeviceIdentity.extractUuid(null, "", "   "))
    }

    @Test
    fun `extractUuid usa o primeiro candidato da lista que tiver match`() {
        val uuid = DeviceIdentity.extractUuid(
            "sem uuid aqui",
            "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "uuid:99999999-9999-9999-9999-999999999999"
        )
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", uuid)
    }

    // ===================== normalizeMac =====================

    @Test
    fun `normalizeMac descarta o placeholder none da API Samsung`() {
        assertNull(DeviceIdentity.normalizeMac("none"))
        assertNull(DeviceIdentity.normalizeMac("None"))
        assertNull(DeviceIdentity.normalizeMac("NONE"))
    }

    @Test
    fun `normalizeMac descarta MAC nulo ou em branco`() {
        assertNull(DeviceIdentity.normalizeMac(null))
        assertNull(DeviceIdentity.normalizeMac("   "))
    }

    @Test
    fun `normalizeMac normaliza separador e caixa`() {
        assertEquals("aa:bb:cc:dd:ee:ff", DeviceIdentity.normalizeMac("AA-BB-CC-DD-EE-FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", DeviceIdentity.normalizeMac("AA:BB:CC:DD:EE:FF"))
    }

    // ===================== candidateKeys =====================

    private fun device(
        deviceId: String? = null,
        mac: String? = null,
        brand: String? = null,
        model: String? = null,
        ip: String = "192.168.0.20",
        protocol: DeviceProtocol = DeviceProtocol.SSDP
    ) = TvDevice(
        name = "TV de teste",
        brand = brand,
        model = model,
        ip = ip,
        port = null,
        protocol = protocol,
        deviceId = deviceId,
        connected = false,
        mac = mac
    )

    @Test
    fun `candidateKeys inclui uuid como primeira chave quando o deviceId contem um`() {
        val tv = device(deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val keys = DeviceIdentity.candidateKeys(tv)
        assertEquals("uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", keys.first())
    }

    @Test
    fun `candidateKeys sem uuid comeca pelo devid prefixado com o protocolo`() {
        val tv = device(deviceId = "servico-sem-uuid", protocol = DeviceProtocol.MDNS)
        val keys = DeviceIdentity.candidateKeys(tv)
        assertEquals("devid:mdns:servico-sem-uuid", keys.first())
    }

    @Test
    fun `candidateKeys nao inclui chave mac quando o MAC e none`() {
        val tv = device(mac = "none")
        val keys = DeviceIdentity.candidateKeys(tv)
        assertTrue(keys.none { it.startsWith("mac:") })
    }

    @Test
    fun `candidateKeys inclui mac normalizado quando valido`() {
        val tv = device(mac = "AA-BB-CC-DD-EE-FF")
        val keys = DeviceIdentity.candidateKeys(tv)
        assertTrue(keys.contains("mac:aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `candidateKeys inclui nome mais modelo apenas quando ambos presentes`() {
        val comAmbos = device(brand = "Samsung", model = "QN90A")
        assertTrue(DeviceIdentity.candidateKeys(comAmbos).contains("nm:samsung:qn90a"))

        val soComMarca = device(brand = "Samsung", model = null)
        assertTrue(DeviceIdentity.candidateKeys(soComMarca).none { it.startsWith("nm:") })
    }

    @Test
    fun `candidateKeys sempre inclui ip como ultima chave`() {
        val tv = device(deviceId = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", ip = "10.0.0.42")
        val keys = DeviceIdentity.candidateKeys(tv)
        assertEquals("ip:10.0.0.42", keys.last())
    }
}
