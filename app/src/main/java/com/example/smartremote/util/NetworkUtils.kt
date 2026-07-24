package com.example.smartremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter

object NetworkUtils {

    /** Retorna o IP local do aparelho na rede Wi-Fi atual, ou null se indisponível. */
    fun getLocalIpAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifiManager?.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            Formatter.formatIpAddress(ip)
        } catch (e: Exception) {
            null
        }
    }

    /** Verifica se o dispositivo está conectado a uma rede Wi-Fi (necessário para descoberta). */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
