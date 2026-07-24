package com.example.smartremote.manager

import com.example.smartremote.model.TvDevice

/**
 * Mantém o estado de conexão com a TV atual. Nesta fase ainda não abre
 * socket/WebSocket real - apenas controla o estado (dispositivo atual,
 * conectando, conectado) para que a UI e os futuros TvController já
 * tenham um contrato estável para usar.
 */
class ConnectionManager {

    private var currentDevice: TvDevice? = null
    private var connecting: Boolean = false
    private var connected: Boolean = false

    fun getCurrentDevice(): TvDevice? = currentDevice

    /**
     * Inicia a conexão com o dispositivo informado. Hoje apenas atualiza o
     * estado interno; a implementação real (TCP/WebSocket por fabricante)
     * entra em uma fase futura, dentro de cada TvController.
     */
    fun connect(device: TvDevice) {
        currentDevice = device
        connecting = true
        connected = false
        // TODO: abrir conexão real (TCP/WebSocket) conforme o protocolo do fabricante.
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
        // TODO: encerrar socket/WebSocket real quando existir.
    }

    fun isConnecting(): Boolean = connecting

    fun isConnected(): Boolean = connected
}
