package com.example.smartremote.model

/**
 * Representa uma tecla/comando do controle remoto de forma genérica,
 * independente de fabricante. Cada [com.example.smartremote.controller.TvController]
 * decide quais valores sabe traduzir para o protocolo do fabricante que
 * implementa - não é obrigatório suportar todos.
 *
 * O enum nasce completo (todos os botões físicos existentes na interface do
 * app, ver activity_main.xml/MainActivity) para que adicionar suporte a um
 * botão novo em uma marca, no futuro, seja só criar a entrada no mapa de
 * tradução daquele TvController - sem precisar alterar esta lista nem a
 * interface TvController novamente.
 */
enum class RemoteKey {

    // ===== Navegação (d-pad) =====
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    HOME,

    // ===== Multimídia =====
    PLAY_PAUSE,

    // ===== Energia =====
    POWER,

    // ===== Volume e canal =====
    VOLUME_UP,
    VOLUME_DOWN,
    CHANNEL_UP,
    CHANNEL_DOWN,

    // ===== Entrada de texto / assistente de voz =====
    KEYBOARD,
    ASSISTANT,

    // ===== Atalhos de apps de streaming =====
    NETFLIX,
    PRIME_VIDEO,
    GLOBOPLAY
}
