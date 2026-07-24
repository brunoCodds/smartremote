package com.example.smartremote.diagnostic

import android.os.Handler
import android.os.Looper
import com.example.smartremote.model.TvDevice
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Ponto único de estado do Modo de Diagnóstico (uso apenas em desenvolvimento).
 *
 * Object (singleton) simples, sem DI: qualquer classe do app - scanners,
 * managers, e futuramente cada TvController concreto (SamsungTizenController,
 * LGWebOSController, AndroidTvController, GoogleTvController, RokuController,
 * FireTvController) - pode chamar os métodos abaixo para reportar estado e
 * eventos, sem nenhum acoplamento com a UI.
 *
 * A MainActivity apenas observa via [addListener]/[removeListener] e
 * renderiza o resultado; toda a lógica de diagnóstico fica aqui.
 *
 * Atualizações são sempre entregues aos listeners no main thread (mesmo
 * padrão de Handler já usado em DeviceScanner/MdnsScanner/SsdpScanner),
 * já que eventos de rede normalmente chegam de background threads.
 */
object DiagnosticManager {

    fun interface Listener {
        fun onDiagnosticUpdated(state: DiagnosticState, logs: List<DiagnosticLogEntry>)
    }

    private const val MAX_LOG_ENTRIES = 100
    private const val TOKEN_VISIBLE_CHARS = 4
    private const val TOKEN_MASK_SUFFIX = "********"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    @Volatile private var state = DiagnosticState()
    private val logs = ArrayDeque<DiagnosticLogEntry>()

    // ===================== OBSERVAÇÃO =====================

    fun addListener(listener: Listener) {
        listeners.add(listener)
        // entrega o estado atual imediatamente para quem acabou de se inscrever
        notifyListener(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ===================== ATUALIZAÇÃO DE ESTADO =====================

    /** Atualiza IP/marca/modelo/SO/protocolo a partir do dispositivo selecionado/conectado. */
    fun updateDevice(device: TvDevice) {
        state = state.copy(
            ip = device.ip,
            brand = device.brand,
            model = device.model,
            os = device.os.name,
            protocol = device.protocol.name
        )
        notifyListeners()
    }

    /** Nome do controlador ativo, ex: "SamsungTizenController". */
    fun setController(name: String?) {
        state = state.copy(controllerName = name)
        notifyListeners()
    }

    /** Estado textual da conexão, ex: "Conectando", "Conectado", "Desconectado". */
    fun setConnectionStatus(status: String) {
        state = state.copy(connectionStatus = status)
        notifyListeners()
    }

    fun setPing(ms: Long?) {
        state = state.copy(pingMs = ms)
        notifyListeners()
    }

    /**
     * Recebe o token/chave real, mas armazena apenas a versão mascarada
     * (ex: "ab3f********") - o valor completo nunca chega à UI.
     */
    fun setToken(rawToken: String?) {
        state = state.copy(tokenMasked = maskToken(rawToken))
        notifyListeners()
    }

    fun setLastCommand(command: String) {
        state = state.copy(lastCommand = command)
        notifyListeners()
    }

    fun setLastResponse(response: String) {
        state = state.copy(lastResponse = response)
        notifyListeners()
    }

    /** Última mensagem de erro relevante, ou null para limpar (ex: após reconectar com sucesso). */
    fun setLastError(message: String?) {
        state = state.copy(lastError = message)
        notifyListeners()
    }

    /**
     * Limpa todo o estado e os logs. Deve ser chamado ao trocar de
     * dispositivo/TV (nova descoberta, nova conexão com outra TV, etc.)
     * para não misturar informações de dispositivos diferentes.
     */
    fun clear() {
        state = DiagnosticState()
        logs.clear()
        notifyListeners()
    }

    // ===================== LOGS =====================

    /**
     * Registra um evento no histórico (ex: "Conectado", "KEY_VOLUP", "Timeout").
     * Mantém no máximo [MAX_LOG_ENTRIES] registros, em ordem cronológica,
     * descartando o mais antigo quando o limite é atingido.
     */
    fun log(message: String, type: DiagnosticLogType = DiagnosticLogType.INFO) {
        if (logs.size >= MAX_LOG_ENTRIES) {
            logs.removeFirst()
        }
        logs.addLast(
            DiagnosticLogEntry(
                timestamp = timeFormat.format(System.currentTimeMillis()),
                type = type,
                message = message
            )
        )
        notifyListeners()
    }

    // ===================== INTERNO =====================

    private fun maskToken(rawToken: String?): String? {
        if (rawToken.isNullOrBlank()) return null
        val visible = rawToken.take(TOKEN_VISIBLE_CHARS)
        return "$visible$TOKEN_MASK_SUFFIX"
    }

    private fun notifyListeners() {
        val snapshotState = state
        val snapshotLogs = logs.toList()
        mainHandler.post {
            listeners.forEach { it.onDiagnosticUpdated(snapshotState, snapshotLogs) }
        }
    }

    private fun notifyListener(listener: Listener) {
        val snapshotState = state
        val snapshotLogs = logs.toList()
        mainHandler.post {
            listener.onDiagnosticUpdated(snapshotState, snapshotLogs)
        }
    }
}
