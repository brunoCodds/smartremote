package com.example.smartremote.util

object Constants {

    // ===== SSDP / UPnP =====
    const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
    const val SSDP_MULTICAST_PORT = 1900

    // @deprecated usar [SSDP_SEARCH_TARGETS]. Mantida só para não quebrar
    // quem ainda referencie um ST único; o SsdpScanner atual já usa a lista.
    const val SSDP_SEARCH_TARGET = "ssdp:all"

    // ===== EVOLUÇÃO - Etapa 1 do plano de descoberta robusta =====
    // Antes o SsdpScanner mandava um único M-SEARCH com ST=ssdp:all. Na
    // teoria do UPnP isso deveria bastar (todo serviço deveria responder a
    // ssdp:all), mas na prática várias TVs implementam SSDP de forma
    // incompleta e só respondem de forma confiável a um ST ESPECÍFICO do
    // ecossistema delas - é a causa mais provável de TVs (ex: a Samsung
    // relatada) que um app comercial encontra e o nosso não. Cada item
    // abaixo é um M-SEARCH separado (mesma multicast, mesmo socket) dentro
    // da mesma rodada de busca:
    //  - ssdp:all                                          -> caso geral/legado
    //  - urn:dial-multiscreen-org:service:dial:1            -> DIAL (Netflix/
    //    YouTube "cast"); suportado por Samsung, LG, Sony, Vizio, Fire TV
    //  - urn:lge-com:service:webos-second-screen:1          -> ST oficial da
    //    LG (UDAP), documentado como mais confiável que ssdp:all para webOS
    //  - urn:schemas-upnp-org:device:MediaRenderer:1        -> schema UPnP
    //    alternativo, citado como funcional por implementações LG de terceiros
    //  - roku:ecp                                           -> ST oficial da
    //    Roku (ECP); sem custo mandar já agora, mesmo antes de haver um
    //    RokuDiscoveryScanner dedicado (etapa futura do plano)
    //
    // A dedup entre STs (a mesma TV física responde a mais de um destes)
    // continua sendo feita pelo SsdpScanner via uuid extraído do USN - não
    // precisa de nenhuma mudança de lógica de parsing por causa desta lista.
    val SSDP_SEARCH_TARGETS = listOf(
        "ssdp:all",
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:lge-com:service:webos-second-screen:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "roku:ecp"
    )

    // Aumentado de 4000 para 5000ms nesta evolução: agora são 5 M-SEARCH
    // (um por ST) na mesma janela de escuta, então um pouco mais de tempo
    // reduz a chance de cortar respostas tardias de TVs mais lentas para
    // responder ao ST correto.
    const val SSDP_TOTAL_TIMEOUT_MS = 5000
    const val SSDP_SOCKET_TIMEOUT_MS = 1500

    // ===== mDNS / NSD =====
    // Service types mais comuns anunciados por Smart TVs na rede local.
    val MDNS_SERVICE_TYPES = listOf(
        "_googlecast._tcp.",       // Chromecast built-in / Android TV / Google TV
        "_androidtvremote2._tcp.", // Android TV Remote v2
        "_dial._tcp.",             // DIAL - usado por Netflix/YouTube em várias Smart TVs
        "_airplay._tcp.",          // AirPlay (algumas Sony/Philips)
        "_webos._tcp."             // LG webOS, quando anunciado via mDNS
    )
    const val MDNS_DISCOVERY_TIMEOUT_MS = 5000L

    // ===== Persistência =====
    const val PREFS_NAME = "smart_remote_prefs"

    // Chave antiga (formato: um único objeto TvDevice). Mantida apenas
    // para a migração automática em DeviceStorage - não usar em código
    // novo. Pode ser removida quando não houver mais base instalada com
    // esse formato antigo.
    const val PREF_KEY_SAVED_DEVICE = "saved_tv_device"

    // Chave nova (formato: JSONArray de TvDevice). Usar esta em todo
    // código novo.
    const val PREF_KEY_SAVED_DEVICES = "saved_tv_devices"

    // ===== Credenciais de pareamento (tokens, client-keys, certificados) =====
    const val CREDENTIALS_PREFS_NAME = "smart_remote_credentials"

    // ===== Samsung Tizen (WebSocket "Samsung Remote Control") =====
    const val SAMSUNG_APP_NAME = "SmartRemote"
    const val SAMSUNG_WS_PORT = 8002
    const val SAMSUNG_WS_PATH = "/api/v2/channels/samsung.remote.control"
    const val SAMSUNG_CREDENTIAL_TYPE = "samsung_token"
    const val SAMSUNG_PAIRING_TIMEOUT_MS = 60_000L

    // ===== LG webOS (WebSocket "SSAP") =====
    // Nome exibido para o usuário no popup de autorização da TV.
    const val LG_APP_NAME = "SmartRemote"
    // Porta segura (wss://), recomendada para webOS 4.x em diante. TVs
    // muito antigas (2014-2017, webOS < 4) usariam ws://3000 sem TLS, mas
    // esse fallback fica fora de escopo nesta fase - ver análise técnica.
    const val LG_WS_PORT = 3001
    const val LG_CREDENTIAL_TYPE = "lg_client_key"
    const val LG_PAIRING_TIMEOUT_MS = 60_000L

    // ===== Samsung - descoberta via API HTTP nativa (SamsungDiscoveryScanner) =====
    // Distinto de SAMSUNG_WS_PORT (8002, usado pelo SamsungTizenController
    // para CONTROLE, depois de pareado). Esta é a porta HTTP em texto puro
    // (sem TLS, sem autenticação) que TVs Samsung 2016+ expõem só para
    // consulta de informação do dispositivo - usada aqui apenas para
    // CONFIRMAR/enriquecer candidatos já vistos por outros scanners nesta
    // rodada de busca (ver SamsungDiscoveryScanner).
    const val SAMSUNG_DISCOVERY_HTTP_PORT = 8001
    const val SAMSUNG_DISCOVERY_API_PATH = "/api/v2/"
    const val SAMSUNG_DISCOVERY_CONNECT_TIMEOUT_MS = 1500
    const val SAMSUNG_DISCOVERY_READ_TIMEOUT_MS = 1500
}
