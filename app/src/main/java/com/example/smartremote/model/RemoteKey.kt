package com.example.smartremote.model

/**
 * Representa uma tecla/comando do controle remoto de forma genérica,
 * independente de fabricante. Cada [com.example.smartremote.controller.TvController]
 * decide quais valores sabe traduzir para o protocolo do fabricante que
 * implementa - não é obrigatório suportar todos.
 *
 * O enum nasce (e continua) completo - representando todos os botões
 * físicos do controle - para que adicionar suporte a um botão novo em uma
 * marca, no futuro, seja só criar a entrada no mapa de tradução daquele
 * TvController, sem alterar esta lista nem a interface TvController de
 * novo.
 *
 * Envio de TEXTO livre (teclado digitado ou voz reconhecida) não é um
 * RemoteKey - é um dado variável, não uma tecla fixa - por isso usa um
 * método próprio, [com.example.smartremote.controller.TvController.sendText].
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
    /** Toggle único de play/pause (botão físico único do controle). */
    PLAY_PAUSE,
    /** Play/Pause/Stop separados - disponíveis para fallback ou uso futuro (ex: outra marca sem toggle único). */
    PLAY,
    PAUSE,
    STOP,

    // ===== Energia e som =====
    POWER,
    MUTE,
    VOLUME_UP,
    VOLUME_DOWN,
    CHANNEL_UP,
    CHANNEL_DOWN,

    // ===== Teclado numérico =====
    NUM_0,
    NUM_1,
    NUM_2,
    NUM_3,
    NUM_4,
    NUM_5,
    NUM_6,
    NUM_7,
    NUM_8,
    NUM_9,

    // ===== Teclas coloridas (funções HbbTV/teletexto) =====
    RED,
    GREEN,
    YELLOW,
    BLUE,

    // ===== Entrada de texto / assistente de voz =====
    KEYBOARD,
    ASSISTANT,

    // ===== Atalhos de apps de streaming =====
    NETFLIX,
    PRIME_VIDEO,
    GLOBOPLAY
}
