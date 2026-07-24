package com.example.smartremote.manager

import android.content.Context
import com.example.smartremote.model.TvDevice
import com.example.smartremote.util.DeviceStorage

/**
 * Ponto único de acesso da UI à TV configurada. Não conhece descoberta
 * (SSDP/mDNS) nem conexão real - apenas guarda/recupera o TvDevice
 * escolhido pelo usuário.
 *
 * Fase futura: quando os TvController concretos existirem, este manager
 * passa a decidir qual usar (com base em `device.brand`/`device.os`) e a
 * delegar ao ConnectionManager a conexão de fato.
 */
class TvManager(private val context: Context) {

    fun saveDevice(device: TvDevice) {
        DeviceStorage.save(context, device)
    }

    fun getSavedDevice(): TvDevice? =
        DeviceStorage.load(context)

    fun hasSavedDevice(): Boolean =
        getSavedDevice() != null

    fun clearSavedDevice() {
        DeviceStorage.clear(context)
    }
}
