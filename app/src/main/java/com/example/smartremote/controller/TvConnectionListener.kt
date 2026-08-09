package com.example.smartremote.controller

/**
 * Callback usado por qualquer TvController para reportar o resultado
 * (assíncrono) de connect(). É comum a todos os fabricantes - Samsung, LG,
 * Android TV, etc. - já que o pareamento normalmente depende de uma
 * confirmação do usuário feita diretamente na TV, e não só de uma resposta
 * imediata de rede.
 */
interface TvConnectionListener {

    /** Conexão autenticada estabelecida - a TV já pode receber comandos. */
    fun onConnected()

    /** A TV está pedindo confirmação do usuário (popup exibido na tela da TV). */
    fun onPairingRequired()

    /** Falha em qualquer etapa do processo (conexão, autenticação, timeout). */
    fun onError(message: String)

    /**
     * *** NOVO - v0.9, item 1 (reconexão automática) ***
     *
     * A conexão que já estava ESTABELECIDA (onConnected já havia sido
     * chamado) caiu, SEM que o usuário tenha pedido isso via
     * [com.example.smartremote.manager.TvManager.disconnect] - diferente
     * de [onError], que cobre falhas durante a TENTATIVA de conexão.
     * Existe separado de onError porque o tratamento é diferente: uma
     * queda inesperada é candidata a reconexão automática (ver
     * [com.example.smartremote.manager.ReconnectionManager]), enquanto um
     * erro de conexão nova normalmente só deve ser mostrado ao usuário.
     *
     * @param recoverable `true` quando a queda foi por rede/timeout (a
     * TvManager deve agendar uma nova tentativa automática com backoff -
     * ver ReconnectionManager). `false` quando a reconexão automática NÃO
     * deve ser tentada sozinha (ex: a TV rejeitou a credencial salva e uma
     * nova confirmação de pareamento seria necessária) - nesse caso a
     * TvManager cancela qualquer retry agendado e exige ação do usuário,
     * evitando "spammar" popups de pareamento na TV sem ninguém olhando.
     *
     * Tem corpo padrão vazio para não quebrar nenhum listener (UI, testes)
     * que já implementava esta interface antes desta mudança.
     */
    fun onConnectionLost(recoverable: Boolean) {}

    /**
     * *** NOVO - v0.9, item 3 (Android TV) ***
     *
     * Diferente do popup de confirmação simples do [onPairingRequired]
     * (Samsung/LG - o usuário só aperta "Permitir" na TV), o pareamento do
     * Android TV exige que o usuário DIGITE no app um código de 6 dígitos
     * hexadecimais que a TV passou a exibir na tela - ver KDoc completo do
     * fluxo em [com.example.smartremote.controller.androidtv.AndroidTvRemoteProtocol.computePairingSecret].
     * A UI deve pedir esse código ao usuário e devolvê-lo via
     * [TvController.submitPairingCode].
     *
     * Corpo padrão vazio pela mesma razão de [onConnectionLost] - não
     * quebra listeners existentes que não passam por este fluxo
     * (Samsung/LG nunca chamam este método).
     */
    fun onPairingCodeRequired() {}
}
