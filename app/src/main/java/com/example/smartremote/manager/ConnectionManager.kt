package com.example.smartremote.manager

import com.example.smartremote.model.TvDevice

/**
 * Mantém o estado de conexão com a TV atual. Nesta fase ainda não abre
 * socket/WebSocket real diretamente (isso é responsabilidade de cada
 * TvController) - apenas controla o estado (dispositivo atual, conectando,
 * conectado) para que a UI e os TvController já tenham um contrato estável
 * para usar.
 *
 * Guarda a conexão de UMA TV por vez, de propósito: o app é um controle
 * remoto, então só faz sentido "apontar" para uma TV de cada vez, mesmo
 * havendo várias pareadas (ver [com.example.smartremote.manager.TvManager]
 * para a lista de pareadas). Este estado é totalmente independente da
 * lista de TVs pareadas - desconectar (ou mesmo esquecer outra TV) nunca
 * afeta o pareamento desta.
 *
 * A identidade da TV conectada é guardada por [TvDevice.stableKey] (não
 * mais pelo objeto [TvDevice] inteiro nem pelo IP), para que comparações
 * continuem corretas mesmo que o IP da TV mude entre o connect() e uma
 * consulta posterior.
 */
class ConnectionManager {

    private var currentDevice: TvDevice? = null
    private var currentDeviceKey: String? = null
    private var connecting: Boolean = false
    private var connected: Boolean = false

    fun getCurrentDevice(): TvDevice? = currentDevice

    /**
     * Inicia a conexão com o dispositivo informado. Hoje apenas atualiza o
     * estado interno; a implementação real (TCP/WebSocket por fabricante)
     * é feita por cada TvController.
     */
    fun connect(device: TvDevice) {
        currentDevice = device
        currentDeviceKey = device.stableKey()
        connecting = true
        connected = false
    }

    /** Marca a conexão como estabelecida. Chamado pelo TvController quando implementado. */
    fun markConnected() {
        connecting = false
        connected = true
    }

    fun disconnect() {
        connecting = false
        connected = false
        currentDevice = null
        currentDeviceKey = null
    }

    fun isConnecting(): Boolean = connecting

    fun isConnected(): Boolean = connected

    /** Se a TV de chave [key] é a atualmente conectada (ou em processo de conexão). */
    fun isConnectedTo(key: String): Boolean = currentDeviceKey == key
}
