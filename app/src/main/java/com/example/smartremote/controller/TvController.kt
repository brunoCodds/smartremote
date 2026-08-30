package com.example.smartremote.controller

import com.example.smartremote.model.RemoteKey

/**
 * Contrato que cada controlador específico de fabricante implementa:
 * SamsungTizenController hoje; LGController, AndroidTVController,
 * GoogleTVController, RokuController, FireTVController em fases futuras.
 *
 * O envio de comandos é genérico via [sendRemoteKey] - cada TvController
 * decide, internamente, quais valores de [RemoteKey] sabe traduzir para o
 * protocolo do seu fabricante (e como reportar os que não suporta). Isso
 * evita que esta interface precise crescer (e quebrar todo controller já
 * existente) toda vez que um comando novo for adicionado - só o enum
 * RemoteKey ganha um valor novo, de forma não-destrutiva.
 */
interface TvController {

    /**
     * @param isAutomaticReconnect *** NOVO - v0.9, item 1 ***. `true`
     * quando esta chamada vem de [com.example.smartremote.manager.ReconnectionManager]
     * (retry silencioso em background ou reconexão proativa ao voltar
     * para o foreground), nunca de uma ação explícita do usuário na tela
     * de descoberta. Implementações devem usar esta flag para NUNCA
     * iniciar um pareamento novo sem credencial salva quando ela for
     * `true` (o que exibiria um popup de confirmação na TV sem que o
     * usuário estivesse olhando/esperando isso) - nesse caso, a
     * implementação deve chamar
     * [TvConnectionListener.onConnectionLost] com `recoverable = false`
     * imediatamente e retornar, em vez de abrir o socket.
     */
    fun connect(listener: TvConnectionListener, isAutomaticReconnect: Boolean = false)
    fun disconnect()
    fun isConnected(): Boolean

    /**
     * Envia um comando genérico para a TV. Implementações devem: (1) não
     * lançar exceção se a TV estiver desconectada - apenas registrar o
     * problema via DiagnosticManager; (2) registrar no DiagnosticManager
     * quando a própria [key] não for suportada por este fabricante.
     */
    fun sendRemoteKey(key: RemoteKey)

    /**
     * Envia um texto livre para a TV (teclado digitado ou voz
     * reconhecida), usando o mecanismo oficial de entrada de texto do
     * fabricante, quando existir - sem simular tecla por tecla. Mesmas
     * regras de [sendRemoteKey]: nunca lança exceção se desconectada, e
     * registra no DiagnosticManager se o fabricante não suportar texto.
     *
     * Importante: nenhum protocolo de TV conhecido informa de volta se
     * havia um campo de texto realmente focado na TV no momento do envio -
     * implementações não devem fingir detectar isso.
     */
    fun sendText(text: String)

    /**
     * Conjunto de valores de [RemoteKey] (do grupo "apps de streaming")
     * que ESTE fabricante sabe de fato lançar - isto é, as chaves
     * presentes no mapa interno de app-launch do controller concreto
     * (ex: SamsungTizenController.APP_LAUNCH_MAP).
     *
     * Existe para que a UI (ex: AppsBottomSheet) possa desabilitar/ocultar
     * botões de apps que este fabricante/modelo não suporta, em vez de
     * deixar o usuário tocar num botão que só vai gerar um "comando não
     * suportado" silencioso no DiagnosticManager. Cada TvController novo
     * (LG, Android TV, Roku, etc.) simplesmente retorna o próprio
     * conjunto - a UI não precisa saber nada de fabricante para reagir a
     * isso.
     */
    fun supportedApps(): Set<RemoteKey>

    /**
     * *** NOVO - v0.9, item 3 (Android TV) ***
     *
     * Recebe o código de 6 dígitos hexadecimais que o usuário digitou no
     * app, em resposta a um [TvConnectionListener.onPairingCodeRequired].
     * Corpo padrão vazio: só [com.example.smartremote.controller.androidtv.AndroidTvController]
     * usa isso de fato - Samsung/LG nunca chamam onPairingCodeRequired, e
     * portanto nunca deveriam receber uma chamada aqui.
     */
    fun submitPairingCode(code: String) {}

    /**
     * *** NOVO - v0.9.4 (modo cursor/mouse) ***
     *
     * Se este fabricante suporta o modo cursor/mouse (ver [sendCursorMove]
     * e [sendCursorClick]). A UI usa isto para habilitar/desabilitar o
     * botão de alternância entre D-pad e cursor na tela principal - nunca
     * deve mostrar a opção e falhar silenciosamente ao tocar (mesmo
     * critério já usado por [supportedApps] para apps de streaming sem App
     * ID confirmado).
     *
     * Default `false`: só [com.example.smartremote.controller.samsung.SamsungTizenController]
     * e [com.example.smartremote.controller.lg.LgWebOsController] sobrescrevem
     * para `true` nesta versão. [com.example.smartremote.controller.androidtv.AndroidTvController]
     * também sobrescreve explicitamente para `false` (em vez de confiar
     * silenciosamente neste default) - ver o KDoc lá para a pesquisa de
     * protocolo que embasa essa decisão.
     */
    fun supportsCursorMode(): Boolean = false

    /**
     * *** NOVO - v0.9.4 ***
     *
     * Move o cursor na TV por um delta RELATIVO ([dx], [dy] em pixels de
     * movimento na tela da TV) - mesmo espírito de um touchpad de
     * notebook, nunca posição absoluta. Implementações que não suportam
     * cursor (ver [supportsCursorMode]) devem tratar isto como no-op,
     * idealmente com um log explicando por quê - mesma regra de [sendText]
     * para fabricantes sem suporte a texto livre. Nunca lançar exceção.
     */
    fun sendCursorMove(dx: Int, dy: Int) {}

    /**
     * *** NOVO - v0.9.4 ***
     *
     * Clique esquerdo na posição atual do cursor (equivalente a um toque
     * curto/tap na área de cursor da UI). Mesmas regras de [sendCursorMove].
     */
    fun sendCursorClick() {}
}
