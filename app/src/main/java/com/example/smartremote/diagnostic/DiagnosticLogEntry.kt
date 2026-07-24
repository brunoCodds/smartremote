package com.example.smartremote.diagnostic

/**
 * Um único evento registrado no log de diagnóstico.
 *
 * @param timestamp horário formatado (HH:mm:ss) em que o evento ocorreu.
 * @param type classificação do evento (ver [DiagnosticLogType]).
 * @param message texto descritivo exibido no painel (ex: "Conectado", "KEY_VOLUP").
 */
data class DiagnosticLogEntry(
    val timestamp: String,
    val type: DiagnosticLogType,
    val message: String
)
