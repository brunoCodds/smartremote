package com.example.smartremote.diagnostic

/**
 * Classifica a natureza de cada evento registrado no log de diagnóstico.
 * Usado apenas para categorização/depuração - não altera a exibição atual
 * do painel (que mantém o texto sempre branco, sem cores/ícones por tipo),
 * mas deixa a estrutura pronta caso isso seja necessário no futuro.
 */
enum class DiagnosticLogType {
    INFO,
    COMMAND,
    RESPONSE,
    NETWORK,
    WARNING,
    ERROR
}
