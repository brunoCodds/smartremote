package com.example.smartremote.util

import android.content.Context
import android.util.Log
import com.example.smartremote.model.DeviceProtocol
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Salva/lê a lista de TVs pareadas pelo usuário usando SharedPreferences +
 * JSON nativo (org.json). Não é uma camada de repositório: é apenas um
 * utilitário de leitura/escrita usado pelo TvManager.
 *
 * A partir desta fase, guarda uma LISTA de dispositivos (uma TV pode estar
 * pareada mesmo sem estar conectada agora), identificados por
 * [TvDevice.stableKey] - e não mais por IP. Isso permite que uma TV mude de
 * IP (renovação de DHCP) sem perder o pareamento: [saveOrUpdate] reconhece a
 * TV pela chave estável e apenas atualiza os dados salvos (incluindo o IP
 * novo), em vez de criar uma entrada duplicada.
 *
 * Compatibilidade: [save] e [load] (singular) continuam existindo para não
 * quebrar código que ainda os chame, mas por baixo já operam sobre a lista
 * nova. Prefira [saveOrUpdate] e [getAll] em código novo.
 */
object DeviceStorage {

    // TAG usada apenas pelos logs temporários de diagnóstico desta etapa
    // (investigação de "TV ainda não suportada").
    private const val DIAG_TAG = "SsdpDiagnostic"

    /**
     * Salva ou atualiza [device] na lista de TVs pareadas, identificando-o
     * por [TvDevice.stableKey]. Se já existir uma TV com a mesma chave,
     * seus dados (incluindo IP, nome, porta) são atualizados no lugar -
     * nunca cria duplicata. Caso contrário, adiciona uma nova entrada.
     */
    fun saveOrUpdate(context: Context, device: TvDevice) {
        Log.d(DIAG_TAG, "DeviceStorage.saveOrUpdate() recebeu: $device (key=${device.stableKey()})")

        val devices = getAll(context).toMutableList()
        val key = device.stableKey()
        val index = devices.indexOfFirst { it.stableKey() == key }

        if (index >= 0) {
            devices[index] = device
            Log.d(DIAG_TAG, "DeviceStorage.saveOrUpdate() atualizou entrada existente (key=$key)")
        } else {
            devices.add(device)
            Log.d(DIAG_TAG, "DeviceStorage.saveOrUpdate() adicionou nova entrada (key=$key)")
        }

        persist(context, devices)
    }

    /** Retorna todas as TVs pareadas. Lista vazia se nenhuma estiver salva. */
    fun getAll(context: Context): List<TvDevice> {
        migrateLegacyIfNeeded(context)

        val raw = prefs(context).getString(Constants.PREF_KEY_SAVED_DEVICES, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { deviceFromJson(array.getJSONObject(i)) }
                    .onFailure { e ->
                        Log.e(DIAG_TAG, "DeviceStorage.getAll() falhou ao parsear item $i: ${array.optJSONObject(i)}", e)
                    }
                    .getOrNull()
            }
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "DeviceStorage.getAll() falhou ao parsear JSON salvo: $raw", e)
            emptyList()
        }
    }

    /** Busca uma TV pareada específica por [TvDevice.stableKey]. */
    fun getByKey(context: Context, key: String): TvDevice? =
        getAll(context).firstOrNull { it.stableKey() == key }

    /** Remove apenas a TV com a chave informada. As demais permanecem intactas. */
    fun remove(context: Context, key: String) {
        val devices = getAll(context).filterNot { it.stableKey() == key }
        persist(context, devices)
        Log.d(DIAG_TAG, "DeviceStorage.remove() removeu key=$key")
    }

    /** Remove todas as TVs pareadas. */
    fun clearAll(context: Context) {
        prefs(context).edit()
            .remove(Constants.PREF_KEY_SAVED_DEVICES)
            .apply()
    }

    // ===================== COMPATIBILIDADE (API antiga, singular) =====================
    // Mantidas para não quebrar chamadas existentes. Por baixo já usam a
    // lista nova. Evitar em código novo - preferir saveOrUpdate/getAll.

    /** @deprecated usar [saveOrUpdate]. Mantido apenas por compatibilidade. */
    fun save(context: Context, device: TvDevice) = saveOrUpdate(context, device)

    /** @deprecated usar [getAll]. Mantido apenas por compatibilidade; retorna a primeira TV pareada. */
    fun load(context: Context): TvDevice? = getAll(context).firstOrNull()

    /** @deprecated usar [clearAll] ou [remove]. Mantido apenas por compatibilidade. */
    fun clear(context: Context) = clearAll(context)

    // ===================== INTERNO =====================

    private fun persist(context: Context, devices: List<TvDevice>) {
        val array = JSONArray()
        devices.forEach { array.put(deviceToJson(it)) }

        prefs(context).edit()
            .putString(Constants.PREF_KEY_SAVED_DEVICES, array.toString())
            .apply()

        Log.d(DIAG_TAG, "DeviceStorage.persist() salvou ${devices.size} dispositivo(s)")
    }

    private fun deviceToJson(device: TvDevice): JSONObject = JSONObject().apply {
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

    private fun deviceFromJson(json: JSONObject): TvDevice {
        val port = json.optInt("port", -1)
        return TvDevice(
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
    }

    /**
     * Migração automática, executada uma única vez: se existir dado no
     * formato antigo (objeto único, [Constants.PREF_KEY_SAVED_DEVICE]) e
     * ainda não existir dado no formato novo (lista,
     * [Constants.PREF_KEY_SAVED_DEVICES]), converte o objeto único em uma
     * lista de 1 item e remove a chave antiga. Transparente para o usuário
     * - quem já tinha uma TV pareada não perde o pareamento nem o token
     * (o token, no CredentialStore, já é indexado por deviceId/IP e não é
     * afetado por esta migração).
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        val p = prefs(context)
        val legacyRaw = p.getString(Constants.PREF_KEY_SAVED_DEVICE, null) ?: return
        val alreadyMigrated = p.contains(Constants.PREF_KEY_SAVED_DEVICES)
        if (alreadyMigrated) {
            // Formato novo já existe; apenas descarta o resíduo antigo.
            p.edit().remove(Constants.PREF_KEY_SAVED_DEVICE).apply()
            return
        }

        Log.d(DIAG_TAG, "DeviceStorage.migrateLegacyIfNeeded() migrando formato antigo: $legacyRaw")
        val migrated = try {
            listOf(deviceFromJson(JSONObject(legacyRaw)))
        } catch (e: Exception) {
            Log.e(DIAG_TAG, "DeviceStorage.migrateLegacyIfNeeded() falhou ao parsear dado antigo; migração abortada", e)
            emptyList()
        }

        val array = JSONArray()
        migrated.forEach { array.put(deviceToJson(it)) }

        p.edit()
            .putString(Constants.PREF_KEY_SAVED_DEVICES, array.toString())
            .remove(Constants.PREF_KEY_SAVED_DEVICE)
            .apply()

        Log.d(DIAG_TAG, "DeviceStorage.migrateLegacyIfNeeded() migração concluída: ${migrated.size} dispositivo(s)")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
