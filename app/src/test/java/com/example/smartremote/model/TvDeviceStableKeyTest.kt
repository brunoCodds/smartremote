package com.example.smartremote.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes de [TvDevice.stableKey] - a proteção contra regressão mais
 * importante deste projeto (ver o KDoc de stableKey() em TvDevice.kt).
 *
 * Esta chave é usada para PAREAMENTO/PERSISTÊNCIA (DeviceStorage) e
 * CREDENCIAIS (CredentialStore) de TVs já salvas por usuários existentes.
 * Se o algoritmo mudar de qualquer forma - mesmo "melhorias" bem
 * intencionadas - TVs já pareadas ficam "órfãs" (a chave salva não bate
 * mais com a chave calculada na próxima busca), fazendo o usuário perder o
 * pareamento e as credenciais salvas sem aviso nenhum.
 *
 * Por isso os testes aqui fixam o valor EXATO esperado para cada
 * prioridade do algoritmo (deviceId -> marca+modelo -> IP), em vez de só
 * testar propriedades genéricas - qualquer mudança no formato da chave
 * (ordem dos segmentos, separador, caixa) quebra um destes testes.
 */
class TvDeviceStableKeyTest {

    private fun device(
        deviceId: String? = null,
        brand: String? = null,
        model: String? = null,
        ip: String = "192.168.0.10",
        protocol: DeviceProtocol = DeviceProtocol.SSDP
    ) = TvDevice(
        name = "TV de teste",
        brand = brand,
        model = model,
        ip = ip,
        port = null,
        protocol = protocol,
        deviceId = deviceId,
        connected = false
    )

    @Test
    fun `prioridade 1 - usa deviceId quando presente`() {
        val tv = device(deviceId = "uuid:1234-5678", brand = "Samsung", model = "QN90", ip = "10.0.0.5")
        assertEquals("ssdp:id:uuid:1234-5678", tv.stableKey())
    }

    @Test
    fun `deviceId tem prioridade mesmo quando brand e model tambem existem`() {
        val comId = device(deviceId = "servico-mdns-123", brand = "LG", model = "OLED55", protocol = DeviceProtocol.MDNS)
        assertEquals("mdns:id:servico-mdns-123", comId.stableKey())
    }

    @Test
    fun `deviceId em branco e ignorado - cai para o proximo criterio`() {
        val tv = device(deviceId = "   ", brand = "Samsung", model = "QN90")
        assertEquals("ssdp:bm:samsung:qn90", tv.stableKey())
    }

    @Test
    fun `prioridade 2 - usa marca e modelo em minusculas quando nao ha deviceId`() {
        val tv = device(deviceId = null, brand = "Samsung", model = "QN90A")
        assertEquals("ssdp:bm:samsung:qn90a", tv.stableKey())
    }

    @Test
    fun `marca sozinha sem modelo nao conta como prioridade 2 - cai para IP`() {
        val tv = device(deviceId = null, brand = "Samsung", model = null, ip = "192.168.1.50")
        assertEquals("ssdp:ip:192.168.1.50", tv.stableKey())
    }

    @Test
    fun `prioridade 3 - usa IP como ultimo recurso`() {
        val tv = device(deviceId = null, brand = null, model = null, ip = "192.168.1.99")
        assertEquals("ssdp:ip:192.168.1.99", tv.stableKey())
    }

    @Test
    fun `protocolo diferente gera prefixo diferente para o mesmo deviceId`() {
        val viaSsdp = device(deviceId = "abc", protocol = DeviceProtocol.SSDP)
        val viaMdns = device(deviceId = "abc", protocol = DeviceProtocol.MDNS)
        assertEquals("ssdp:id:abc", viaSsdp.stableKey())
        assertEquals("mdns:id:abc", viaMdns.stableKey())
    }
}
