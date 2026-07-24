package com.example.smartremote.util

import android.content.Context

/**
 * Armazena credenciais de pareamento (tokens, client-keys, certificados),
 * separadas do TvDevice salvo pelo DeviceStorage.
 *
 * Genérico por fabricante: cada TvController escolhe o [type] que usa
 * (ex: "samsung_token", futuramente "lg_client_key", "android_tv_cert") e
 * a chave do dispositivo (TvDevice.deviceId, com fallback para o IP).
 * Cada combinação (deviceId + type) guarda seu próprio valor, então no
 * futuro um mesmo dispositivo pode ter mais de uma credencial se o
 * protocolo do fabricante exigir - sem precisar redesenhar nada aqui.
 */
object CredentialStore {

    fun save(context: Context, deviceId: String, type: String, value: String) {
        prefs(context).edit()
            .putString(key(deviceId, type), value)
            .apply()
    }

    fun get(context: Context, deviceId: String, type: String): String? {
        return prefs(context).getString(key(deviceId, type), null)
    }

    fun clear(context: Context, deviceId: String, type: String) {
        prefs(context).edit()
            .remove(key(deviceId, type))
            .apply()
    }

    private fun key(deviceId: String, type: String): String = "${deviceId}__$type"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.CREDENTIALS_PREFS_NAME, Context.MODE_PRIVATE)
}
