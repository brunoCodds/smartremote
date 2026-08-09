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
}
