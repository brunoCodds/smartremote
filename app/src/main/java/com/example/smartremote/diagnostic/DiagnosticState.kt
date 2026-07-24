package com.example.smartremote.diagnostic

/**
 * Snapshot imutável do estado atual de diagnóstico, mantido pelo
 * [DiagnosticManager]. A MainActivity apenas lê esses campos para
 * renderizar o painel - nenhum campo é calculado por ela.
 */
data class DiagnosticState(
    val ip: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val os: String? = null,
    val controllerName: String? = null,
    val protocol: String? = null,
    val connectionStatus: String = "Desconectado",
    val pingMs: Long? = null,
    val tokenMasked: String? = null,
    val lastCommand: String? = null,
    val lastResponse: String? = null,
    val lastError: String? = null
)
