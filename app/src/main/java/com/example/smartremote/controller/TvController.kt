package com.example.smartremote.controller

/**
 * Contrato que cada controlador específico de fabricante vai implementar
 * em fases futuras: SamsungController, LGController, AndroidTVController,
 * GoogleTVController, RokuController, FireTVController.
 *
 * Nenhuma implementação concreta existe ainda - apenas o contrato, para que
 * a arquitetura já esteja pronta para recebê-las sem retrabalho.
 */
interface TvController {
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean

    fun powerToggle()
    fun volumeUp()
    fun volumeDown()
    fun channelUp()
    fun channelDown()
    fun dpadUp()
    fun dpadDown()
    fun dpadLeft()
    fun dpadRight()
    fun dpadOk()
    fun back()
    fun home()
    fun playPause()
    fun sendKey(key: String)
}
