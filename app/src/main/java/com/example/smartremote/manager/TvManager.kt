package com.example.smartremote.manager

import android.content.Context
import android.util.Log
import com.example.smartremote.R
import com.example.smartremote.controller.TvConnectionListener
import com.example.smartremote.controller.TvController
import com.example.smartremote.controller.androidtv.AndroidTvController
import com.example.smartremote.controller.androidtv.AndroidTvKeystoreManager
import com.example.smartremote.controller.lg.LgWebOsController
import com.example.smartremote.controller.samsung.SamsungTizenController
import com.example.smartremote.diagnostic.DiagnosticLogType
import com.example.smartremote.diagnostic.DiagnosticManager
import com.example.smartremote.model.RemoteKey
import com.example.smartremote.model.TvDevice
import com.example.smartremote.model.TvOperatingSystem
import com.example.smartremote.util.Constants
import com.example.smartremote.util.CredentialStore
import com.example.smartremote.util.DeviceStorage

/**
 * Ponto único de acesso da UI às TVs pareadas e à conexão ativa.
 *
 * A partir desta fase suporta MÚLTIPLAS TVs pareadas simultaneamente
 * ([getPairedDevices]), mas continua controlando apenas UMA conexão ativa
 * por vez ([ConnectionManager]) - faz sentido para um controle remoto: o
 * usuário sempre está "apontando" para uma TV de cada vez, mesmo tendo
 * várias cadastradas.
 *
 * Continua sendo um singleton (object) pelo mesmo motivo de antes: guarda o
 * TvController e o estado de conexão realmente ativos, que não podem se
 * perder ao trocar de Activity.
 *
 * Cada TV é identificada por [TvDevice.stableKey] (deviceId/USN quando
 * disponível, com fallback isolado em TvDevice) - nunca mais por IP puro.
 * Isso permite que uma TV pareada mude de IP (renovação de DHCP) sem que o
 * app a trate como um dispositivo novo ou perca o token salvo.
 *
 * Decide qual TvController usar com base em `device.os` (Tizen -> Samsung,
 * WEBOS -> LG; os demais fabricantes entram aqui conforme forem
 * implementados) e delega a ele a conexão de fato, mantendo o
 * ConnectionManager como registro simples de estado.
 *
 * *** ETAPA 1 (continuação) - checkpoint 6/6 do rastreamento de
 * pareamento LG ***: o wrapper de [TvConnectionListener] passado a
 * [TvController.connect] agora loga explicitamente quando CADA callback
 * (onConnected/onPairingRequired/onError) chega até aqui vindo do
 * controller, e quando ele é repassado pro listener da UI - para
 * confirmar que a ponte controller -> TvManager -> UI não é o elo que
 * está quebrando o fluxo de pareamento LG.
 *
 * *** ETAPA 2 - correção "reconexão automática sempre usa a primeira TV
 * salva" ***: [pairDevice] agora marca explicitamente, via
 * [TvDevice.connected], qual foi a ÚLTIMA TV efetivamente conectada
 * (desmarcando as demais), e [getLastConnectedDevice] usa essa marcação
 * para a reconexão automática ao abrir o app (ver MainActivity) - antes
 * disso, a reconexão automática usava getSavedDevice() (primeira da
 * lista), então trocar de TV pareada não fazia efeito nenhum na
 * reconexão do próximo lançamento do app.
 */
object TvManager {

    // TAG usada apenas pelos logs temporários de diagnóstico desta etapa
    // (investigação de "TV ainda não suportada").
    private const val DIAG_TAG = "SsdpDiagnostic"

    private val connectionManager = ConnectionManager()
    private var currentController: TvController? = null

    // ===================== API NOVA (plural - múltiplas TVs) =====================

    /**
     * Pareia (salva) [device], ou atualiza os dados de uma TV já pareada
     * com a mesma chave estável, e marca esta TV como a ÚLTIMA
     * efetivamente conectada (ver [TvDevice.connected] / [getLastConnectedDevice]),
     * desmarcando qualquer outra TV que estivesse marcada assim
     * anteriormente. As demais TVs salvas não são afetadas de nenhuma
     * outra forma - continuam pareadas normalmente.
     */
    fun pairDevice(context: Context, device: TvDevice) {
        DeviceStorage.saveOrUpdate(context, device.copy(connected = true))

        DeviceStorage.getAll(context)
            .filter { it.stableKey() != device.stableKey() && it.connected }
            .forEach { DeviceStorage.saveOrUpdate(context, it.copy(connected = false)) }
    }

    /** Todas as TVs pareadas no momento. Lista vazia se nenhuma. */
    fun getPairedDevices(context: Context): List<TvDevice> =
        DeviceStorage.getAll(context)

    /** Se existe alguma TV pareada com esta chave estável. */
    fun isPaired(context: Context, key: String): Boolean =
        DeviceStorage.getByKey(context, key) != null

    /**
     * TV marcada como a última efetivamente conectada (ver [pairDevice]).
     * Usada para a reconexão automática ao abrir o app - diferente de
     * [getSavedDevice] (deprecated), que sempre retorna a primeira TV da
     * lista, independente de qual foi usada por último.
     *
     * Fallback para a primeira TV salva caso nenhuma esteja marcada (ex:
     * dado migrado do formato antigo de armazenamento, anterior a esta
     * marcação existir).
     */
    fun getLastConnectedDevice(context: Context): TvDevice? {
        val devices = getPairedDevices(context)
        return devices.firstOrNull { it.connected } ?: devices.firstOrNull()
    }

    /**
     * Esquece a TV de chave [key]: remove o pareamento e a credencial
     * associada (quando o fabricante da TV tiver um tipo de credencial
     * conhecido - ver [credentialTypeFor]). Não afeta nenhuma outra TV
     * pareada. Se a TV esquecida for a que está conectada no momento, a
     * conexão ativa também é encerrada.
     */
    fun forgetDevice(context: Context, key: String) {
        val device = DeviceStorage.getByKey(context, key)

        if (connectionManager.isConnectedTo(key)) {
            disconnect()
        }

        DeviceStorage.remove(context, key)

        device?.let {
            val credentialDeviceId = it.deviceId ?: it.ip
            if (it.os == TvOperatingSystem.ANDROID_TV || it.os == TvOperatingSystem.GOOGLE_TV) {
                // *** v0.9, item 3 ***: diferente de Samsung/LG (um
                // simples CredentialStore.clear() já basta), aqui existe
                // uma entrada de verdade no AndroidKeyStore que precisa
                // ser apagada explicitamente - ver KDoc de
                // AndroidTvKeystoreManager.deleteKeyPair() (que também já
                // limpa o CredentialStore por baixo, então não chamamos
                // CredentialStore.clear() de novo aqui).
                AndroidTvKeystoreManager(context, credentialDeviceId).deleteKeyPair()
            } else {
                val credentialType = credentialTypeFor(it.os)
                if (credentialType != null) {
                    CredentialStore.clear(context, credentialDeviceId, credentialType)
                }
            }
        }

        Log.d(DIAG_TAG, "TvManager.forgetDevice() removeu key=$key")
    }

    // ===================== API ANTIGA (singular) - mantida por compatibilidade =====================
    // Por baixo já delegam para a lista nova. Preferir a API plural acima
    // em código novo.

    /** @deprecated usar [pairDevice]. Mantido apenas por compatibilidade. */
    fun saveDevice(context: Context, device: TvDevice) = pairDevice(context, device)

    /**
     * @deprecated usar [getLastConnectedDevice] (reconexão automática) ou
     * [getPairedDevices] (lista completa). Mantido apenas por
     * compatibilidade; retorna sempre a PRIMEIRA TV pareada da lista,
     * independente de qual foi conectada por último - por isso não deve
     * ser usado para decidir com qual TV reconectar automaticamente.
     */
    fun getSavedDevice(context: Context): TvDevice? = getPairedDevices(context).firstOrNull()

    /** @deprecated usar [getPairedDevices].isNotEmpty(). Mantido apenas por compatibilidade. */
    fun hasSavedDevice(context: Context): Boolean = getPairedDevices(context).isNotEmpty()

    /** @deprecated usar [forgetDevice]. Mantido apenas por compatibilidade; remove TODAS as TVs pareadas. */
    fun clearSavedDevice(context: Context) {
        disconnect()
        DeviceStorage.clearAll(context)
    }

    // ===================== CONEXÃO =====================

    /**
     * Inicia a conexão/pareamento com [device], escolhendo o TvController
     * correto conforme o sistema operacional detectado na descoberta.
     * Qualquer conexão anterior é encerrada antes de iniciar uma nova, e o
     * DiagnosticManager é limpo para não misturar logs de dispositivos
     * diferentes.
     */
    /**
     * Inicia a conexão/pareamento com [device], escolhendo o TvController
     * correto conforme o sistema operacional detectado na descoberta.
     * Qualquer conexão anterior é encerrada antes de iniciar uma nova, e o
     * DiagnosticManager é limpo para não misturar logs de dispositivos
     * diferentes.
     *
     * @param isAutomaticReconnect *** NOVO - v0.9, item 1 ***. `true`
     * quando esta chamada vem de [ReconnectionManager] (retry silencioso
     * em background) ou de [reconnectIfNeeded] (proativa ao voltar pro
     * foreground) - nunca de uma ação explícita do usuário. Repassado ao
     * controller (ver [TvController.connect]) e usado aqui para: (1) não
     * limpar o DiagnosticManager a cada tentativa de retry, preservando o
     * histórico para o usuário entender o que aconteceu, e (2) decidir o
     * que fazer quando o controller sinaliza [TvConnectionListener.onError]
     * durante uma tentativa automática (ver abaixo).
     */
    fun connect(
        context: Context,
        device: TvDevice,
        listener: TvConnectionListener,
        isAutomaticReconnect: Boolean = false
    ) {
        Log.d(DIAG_TAG, "TvManager.connect() recebeu device: $device (key=${device.stableKey()}, automatico=$isAutomaticReconnect)")
        currentController?.disconnect()
        if (!isAutomaticReconnect) {
            DiagnosticManager.clear()
        }

        val controller = createControllerFor(context, device)
        if (controller == null) {
            // *** v0.9.3, item 1 ***: este retorno antecipado não passa
            // pelo wrapper de TvConnectionListener abaixo (que é quem
            // normalmente aciona ReconnectionManager.scheduleReconnect/cancel
            // para desligar o indicador) - se não desligarmos aqui, uma
            // reconexão automática com uma TV que ficou sem controller
            // suportado deixaria o indicador "Reconectando..." preso ligado
            // para sempre.
            if (isAutomaticReconnect) {
                ReconnectionManager.cancel()
            }
            listener.onError(context.getString(R.string.error_tv_not_supported))
            return
        }

        currentController = controller
        connectionManager.connect(device)

        controller.connect(
            object : TvConnectionListener {
                override fun onConnected() {
                    DiagnosticManager.log(
                        "[LG-PAIRING] 6/6 - TvManager.onConnected() recebido do controller (${controller::class.simpleName}); marcando ConnectionManager e repassando para o listener da UI",
                        DiagnosticLogType.INFO
                    )
                    connectionManager.markConnected()
                    // *** v0.9, item 1 ***: qualquer reconexão automática
                    // pendente (backoff agendado) deixa de fazer sentido -
                    // já estamos conectados de novo.
                    ReconnectionManager.onReconnected()
                    // *** NOVO - v0.9.3 (correção: Ping nunca era medido) ***
                    controlPortFor(device.os)?.let { port -> PingMonitor.start(device.ip, port) }
                    listener.onConnected()
                    DiagnosticManager.log("[LG-PAIRING] 6/6 - listener.onConnected() da UI retornou (repasse concluído)", DiagnosticLogType.INFO)
                }

                override fun onPairingRequired() {
                    DiagnosticManager.log(
                        "[LG-PAIRING] TvManager.onPairingRequired() recebido do controller (${controller::class.simpleName}); repassando para o listener da UI",
                        DiagnosticLogType.INFO
                    )
                    // *** v0.9, item 1 ***: se a TV está pedindo confirmação
                    // de novo - mesmo que tenha sido uma tentativa
                    // automática que chegou até aqui (não deveria, já que os
                    // controllers evitam iniciar pareamento novo quando
                    // isAutomaticReconnect=true e não há credencial salva,
                    // mas cobre também o caso de credencial salva rejeitada
                    // NA HORA por um controller que ainda assim decida pedir
                    // confirmação) - paramos qualquer retry automático
                    // agendado, para não virar uma enxurrada de popups na TV.
                    ReconnectionManager.cancel()
                    listener.onPairingRequired()
                }

                override fun onError(message: String) {
                    DiagnosticManager.log(
                        "[LG-PAIRING] TvManager.onError() recebido do controller (${controller::class.simpleName}): $message",
                        DiagnosticLogType.ERROR
                    )
                    connectionManager.disconnect()
                    PingMonitor.stop() // *** NOVO - v0.9.3 ***
                    listener.onError(message)
                    if (isAutomaticReconnect) {
                        // Tentativa automática falhou por um motivo que não
                        // foi sinalizado como "não recuperável" via
                        // onConnectionLost(false) (ex: TV inalcançável na
                        // rede agora mesmo) - continua tentando com backoff.
                        ReconnectionManager.scheduleReconnect(context.applicationContext, device)
                    }
                }

                override fun onConnectionLost(recoverable: Boolean) {
                    DiagnosticManager.log(
                        "[LG-PAIRING] TvManager.onConnectionLost(recoverable=$recoverable) recebido do controller (${controller::class.simpleName})",
                        DiagnosticLogType.INFO
                    )
                    connectionManager.disconnect()
                    PingMonitor.stop() // *** NOVO - v0.9.3 ***
                    listener.onConnectionLost(recoverable)
                    if (recoverable) {
                        ReconnectionManager.scheduleReconnect(context.applicationContext, device)
                    } else {
                        // Ex: credencial salva foi rejeitada, ou reconexão
                        // automática não tinha credencial nenhuma para usar
                        // - exige ação do usuário, não adianta insistir.
                        ReconnectionManager.cancel()
                    }
                }
            },
            isAutomaticReconnect
        )
    }

    /**
     * *** NOVO - v0.9, item 1 ***
     *
     * Tenta reconectar PROATIVAMENTE com a última TV conectada
     * ([getLastConnectedDevice]), em vez de ficar passivo esperando o
     * usuário mandar um comando para só aí descobrir que está
     * desconectado. Chamada pela MainActivity ao voltar para o
     * foreground (onStart) - cobre tanto a abertura "fria" do app quanto
     * o retorno de background.
     *
     * Não faz nada se: já existe uma conexão ativa, ou não há nenhuma TV
     * pareada. Usa `isAutomaticReconnect = true` (mesma lógica de
     * segurança contra popups de pareamento sem o usuário olhando que se
     * aplica ao retry em background).
     */
    fun reconnectIfNeeded(context: Context) {
        if (isConnected()) return
        val device = getLastConnectedDevice(context) ?: return

        // *** NOVO - v0.9.3, item 1 ***: liga o indicador já nesta primeira
        // tentativa proativa, antes mesmo de qualquer backoff ser agendado -
        // se ela falhar, o onError abaixo aciona o ReconnectionManager, que
        // mantém o flag ligado; se tiver sucesso, onConnected desliga via
        // ReconnectionManager.onReconnected().
        DiagnosticManager.setAutoReconnecting(true)
        DiagnosticManager.log("Reconexão proativa ao voltar para o app (device=${device.stableKey()})", DiagnosticLogType.INFO)
        connect(context, device, object : TvConnectionListener {
            override fun onConnected() {}
            override fun onPairingRequired() {}
            override fun onError(message: String) {}
        }, isAutomaticReconnect = true)
    }

    /** Encerra a conexão ativa, se existir. Seguro chamar mesmo sem conexão. Não afeta o pareamento. */
    fun disconnect() {
        // *** v0.9, item 1 ***: desconexão pedida pelo usuário cancela
        // qualquer reconexão automática agendada - senão o app tentaria
        // reconectar sozinho segundos depois do usuário ter pedido
        // justamente o contrário.
        ReconnectionManager.cancel()
        PingMonitor.stop() // *** NOVO - v0.9.3 ***
        currentController?.disconnect()
        currentController = null
        connectionManager.disconnect()
    }

    /**
     * *** NOVO - v0.9.3 ***: porta de controle usada por [PingMonitor] para
     * medir a latência até a TV, por sistema operacional. `null` para
     * sistemas ainda sem TvController implementado (o ping simplesmente
     * não roda nesse caso - não há conexão ativa de qualquer forma).
     */
    private fun controlPortFor(os: TvOperatingSystem): Int? = when (os) {
        TvOperatingSystem.TIZEN -> Constants.SAMSUNG_WS_PORT
        TvOperatingSystem.WEBOS -> Constants.LG_WS_PORT
        TvOperatingSystem.ANDROID_TV, TvOperatingSystem.GOOGLE_TV -> Constants.ANDROID_TV_REMOTE_PORT
        else -> null
    }

    fun isConnected(): Boolean = connectionManager.isConnected()

    /**
     * Envia um comando genérico (ver [RemoteKey]) para a TV atualmente
     * conectada. Não sabe nada sobre o protocolo do fabricante - só
     * delega ao [TvController] ativo, que decide como (ou se) traduz essa
     * tecla. Se não houver nenhuma TV conectada no momento, registra o
     * mesmo aviso genérico que o controller usaria para o caso de
     * desconexão - aqui de forma independente de fabricante.
     *
     * *** CORREÇÃO - v0.9.3 ("Último comando" do painel simples nunca
     * aparecia para TVs LG) ***: `setLastCommand` é chamado AQUI, no único
     * ponto de entrada por onde toda tecla passa, independente de
     * fabricante - antes disso, cada TvController precisava lembrar de
     * chamar `DiagnosticManager.setLastCommand()` no seu próprio código, e
     * o `LgWebOsController.sendRemoteKey()` nunca chamava (só registrava
     * no log técnico) para o caminho comum de teclas do controle remoto,
     * então "Último comando" ficava sempre vazio para TVs LG. Centralizar
     * aqui garante que funciona para todo fabricante, inclusive os que
     * ainda serão implementados no futuro (Roku, Fire TV etc.), sem
     * depender de cada controller lembrar de instrumentar isso.
     */
    fun sendRemoteKey(key: RemoteKey) {
        val controller = currentController
        if (controller == null) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        DiagnosticManager.setLastCommand(key.name)
        controller.sendRemoteKey(key)
    }

    /**
     * Envia um texto livre (teclado digitado ou voz reconhecida) para a
     * TV atualmente conectada. Mesmo padrão de [sendRemoteKey]: não sabe
     * nada sobre o protocolo do fabricante, só delega - e também
     * centraliza [DiagnosticManager.setLastCommand] pelo mesmo motivo.
     */
    fun sendText(text: String) {
        val controller = currentController
        if (controller == null) {
            DiagnosticManager.log("Falha ao enviar comando: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        DiagnosticManager.setLastCommand("Texto (${text.length} caractere(s))")
        controller.sendText(text)
    }

    /** Se a TV de chave [key] é a que está conectada (ou conectando) no momento. */
    fun isConnectedTo(key: String): Boolean = connectionManager.isConnectedTo(key)

    /**
     * Apps que a TV/fabricante atualmente conectado sabe abrir (ver
     * [TvController.supportedApps]). Vazio se não houver TV conectada.
     * Só delega ao controller ativo - TvManager continua sem saber nada
     * de fabricante.
     */
    fun getSupportedApps(): Set<RemoteKey> = currentController?.supportedApps() ?: emptySet()

    /**
     * *** NOVO - v0.9, item 3 (Android TV) ***
     *
     * Repassa o código de 6 dígitos digitado pelo usuário (em resposta a
     * [TvConnectionListener.onPairingCodeRequired]) para o controller
     * ativo. Só tem efeito de fato durante um pareamento Android TV em
     * andamento - para qualquer outro fabricante/situação, o controller
     * ignora silenciosamente (ver corpo padrão de [TvController.submitPairingCode]).
     */
    fun submitPairingCode(code: String) {
        currentController?.submitPairingCode(code)
    }

    /**
     * *** NOVO - v0.9.4 (modo cursor/mouse) ***: se a TV atualmente
     * conectada suporta o modo cursor (ver [TvController.supportsCursorMode]).
     * `false` sem nenhuma TV conectada - a UI trata isso do mesmo jeito que
     * [getSupportedApps] retornando vazio (botão de alternância desabilitado).
     */
    fun isCursorModeSupported(): Boolean = currentController?.supportsCursorMode() ?: false

    /**
     * *** NOVO - v0.9.4 ***: delega o movimento relativo do cursor ([dx],
     * [dy]) ao controller ativo.
     *
     * DECISÃO: diferente de [sendRemoteKey]/[sendText], propositalmente
     * NÃO centraliza `DiagnosticManager.setLastCommand()`/`log()` aqui. A
     * centralização deles na v0.9.3 fazia sentido porque uma tecla é um
     * evento discreto e raro (um toque = uma tecla). Um gesto de arrastar
     * no modo cursor gera um evento de movimento a cada ~30-50ms (ver
     * throttle na UI) - logar cada um encheria o log cronológico do
     * Diagnóstico Aprofundado (limite de 100 entradas) em poucos segundos
     * de uso normal, sem nenhum valor de depuração adicional além de "o
     * modo cursor está sendo usado". Cada controller continua livre para
     * logar seus próprios erros pontuais (ex: pointer socket indisponível),
     * só não deve logar todo movimento bem-sucedido.
     */
    fun sendCursorMove(dx: Int, dy: Int) {
        currentController?.sendCursorMove(dx, dy)
    }

    /**
     * *** NOVO - v0.9.4 ***: delega o clique do cursor ao controller ativo.
     * Diferente de [sendCursorMove], um clique É um evento discreto e pouco
     * frequente (um tap, não um stream de arrasto) - por isso centraliza
     * `DiagnosticManager.setLastCommand()` aqui, mesmo critério já usado
     * por [sendRemoteKey]/[sendText].
     */
    fun sendCursorClick() {
        val controller = currentController
        if (controller == null) {
            DiagnosticManager.log("Falha ao enviar clique do cursor: TV desconectada", DiagnosticLogType.ERROR)
            return
        }
        DiagnosticManager.setLastCommand("Cursor: clique")
        controller.sendCursorClick()
    }

    /** Chave (stableKey) da TV atualmente conectada, ou null se nenhuma conexão ativa. */
    fun getConnectedDeviceKey(): String? {
        val device = connectionManager.getCurrentDevice() ?: return null
        return device.stableKey().takeIf { connectionManager.isConnected() }
    }

    private fun createControllerFor(context: Context, device: TvDevice): TvController? {
        val controller = when (device.os) {
            TvOperatingSystem.TIZEN -> SamsungTizenController(context.applicationContext, device)
            TvOperatingSystem.WEBOS -> LgWebOsController(context.applicationContext, device)
            TvOperatingSystem.ANDROID_TV, TvOperatingSystem.GOOGLE_TV ->
                AndroidTvController(context.applicationContext, device)
            else -> null
        }
        Log.d(
            DIAG_TAG,
            "TvManager.createControllerFor() -> os=${device.os}, controller=${controller?.let { it::class.simpleName } ?: "NULO (não suportado)"}"
        )
        return controller
    }

    /**
     * Tipo de credencial ([CredentialStore]) usado por cada fabricante,
     * para saber o que limpar em [forgetDevice]. Único lugar que precisa
     * ganhar uma linha nova quando um fabricante novo passar a salvar
     * credencial (certificado Android TV, etc.) - o CredentialStore em si
     * não precisa mudar.
     */
    private fun credentialTypeFor(os: TvOperatingSystem): String? = when (os) {
        TvOperatingSystem.TIZEN -> Constants.SAMSUNG_CREDENTIAL_TYPE
        TvOperatingSystem.WEBOS -> Constants.LG_CREDENTIAL_TYPE
        TvOperatingSystem.ANDROID_TV, TvOperatingSystem.GOOGLE_TV -> Constants.ANDROID_TV_CREDENTIAL_TYPE
        else -> null
    }
}
