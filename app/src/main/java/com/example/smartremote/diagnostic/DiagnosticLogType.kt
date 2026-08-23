package com.example.smartremote.diagnostic

/**
 * Classifica a natureza de cada evento registrado no log de diagnóstico.
 *
 * Cada tipo determina a cor da linha correspondente no Diagnóstico
 * Aprofundado (ver [DiagnosticLogAdapter]) - o stream de log não aparece
 * mais no painel simples (removido a pedido explícito: era texto técnico
 * de protocolo sem valor para o usuário comum). Antes disso era usado
 * apenas para categorização/depuração, sem efeito visual nenhum.
 */
enum class DiagnosticLogType {
    INFO,
    COMMAND,
    RESPONSE,
    NETWORK,
    WARNING,
    ERROR
}
