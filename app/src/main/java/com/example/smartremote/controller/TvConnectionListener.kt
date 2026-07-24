package com.example.smartremote.controller

/**
 * Callback usado por qualquer TvController para reportar o resultado
 * (assíncrono) de connect(). É comum a todos os fabricantes - Samsung, LG,
 * Android TV, etc. - já que o pareamento normalmente depende de uma
 * confirmação do usuário feita diretamente na TV, e não só de uma resposta
 * imediata de rede.
 */
interface TvConnectionListener {

    /** Conexão autenticada estabelecida - a TV já pode receber comandos. */
    fun onConnected()

    /** A TV está pedindo confirmação do usuário (popup exibido na tela da TV). */
    fun onPairingRequired()

    /** Falha em qualquer etapa do processo (conexão, autenticação, timeout). */
    fun onError(message: String)
}
