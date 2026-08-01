package com.example.smartremote.model

/**
 * Protocolo/mecanismo pelo qual o dispositivo foi descoberto na rede local.
 *
 * Usado como prefixo em [TvDevice.stableKey] só para evitar colisão teórica
 * entre identificadores de origens diferentes - adicionar um valor novo
 * aqui NUNCA muda a chave de dispositivos já existentes com os valores
 * antigos (SSDP/MDNS), então é seguro para TVs já pareadas.
 */
enum class DeviceProtocol {
    SSDP,
    MDNS,

    /**
     * Confirmação via API HTTP nativa da Samsung (`:8001/api/v2/`) - ver
     * SamsungDiscoveryScanner. Não é um protocolo de DESCOBERTA "do zero"
     * (não varre a rede sozinho), só confirma/enriquece um IP candidato já
     * visto por outro scanner nesta mesma rodada - mas ainda é uma FONTE de
     * identificação própria, por isso tem seu próprio valor aqui (evita
     * marcar como SSDP algo que não veio de SSDP).
     */
    SAMSUNG_HTTP
}
