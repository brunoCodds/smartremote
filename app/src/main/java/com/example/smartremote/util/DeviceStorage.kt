package com.example.smartremote.util

import android.content.Context
import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import org.json.JSONObject

/**
 * Salva/lê o TvDevice escolhido pelo usuário usando SharedPreferences + JSON
 * nativo (org.json). Não é uma camada de repositório: é apenas um utilitário
 * de leitura/escrita usado pelo TvManager.
 */
object DeviceStorage {

    fun save(context: Context, device: TvDevice) {
        val json = JSONObject().apply {
            put("name", device.name)
            put("brand", device.brand)
            put("model", device.model)
            put("ip", device.ip)
            put("port", device.port ?: -1)
            put("protocol", device.protocol.name)
            put("os", device.os.name)
            put("deviceId", device.deviceId)
            put("connected", device.connected)
        }
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_KEY_SAVED_DEVICE, json.toString())
            .apply()
    }

    fun load(context: Context): TvDevice? {
        val raw = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_KEY_SAVED_DEVICE, null) ?: return null

        return try {
            val json = JSONObject(raw)
            val port = json.optInt("port", -1)
            TvDevice(
                name = json.getString("name"),
                brand = json.optString("brand").takeIf { it.isNotBlank() },
                model = json.optString("model").takeIf { it.isNotBlank() },
                ip = json.getString("ip"),
                port = if (port != -1) port else null,
                protocol = DeviceProtocol.valueOf(json.getString("protocol")),
                os = TvOperatingSystem.valueOf(json.optString("os", TvOperatingSystem.UNKNOWN.name)),
                deviceId = json.optString("deviceId").takeIf { it.isNotBlank() },
                connected = json.optBoolean("connected", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.PREF_KEY_SAVED_DEVICE)
            .apply()
    }
}
