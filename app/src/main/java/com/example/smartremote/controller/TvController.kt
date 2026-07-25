package com.example.smartremote.controller

import com.example.smartremote.model.RemoteKey

/**
 * Contrato que cada controlador específico de fabricante implementa:
 * SamsungTizenController hoje; LGController, AndroidTVController,
 * GoogleTVController, RokuController, FireTVController em fases futuras.
 *
 * O envio de comandos é genérico via [sendRemoteKey] - cada TvController
 * decide, internamente, quais valores de [RemoteKey] sabe traduzir para o
 * protocolo do seu fabricante (e como reportar os que não suporta). Isso
 * evita que esta interface precise crescer (e quebrar todo controller já
 * existente) toda vez que um comando novo for adicionado - só o enum
 * RemoteKey ganha um valor novo, de forma não-destrutiva.
 */
interface TvController {
    fun connect(listener: TvConnectionListener)
    fun disconnect()
    fun isConnected(): Boolean

    /**
     * Envia um comando genérico para a TV. Implementações devem: (1) não
     * lançar exceção se a TV estiver desconectada - apenas registrar o
     * problema via DiagnosticManager; (2) registrar no DiagnosticManager
     * quando a própria [key] não for suportada por este fabricante.
     */
    fun sendRemoteKey(key: RemoteKey)
}
