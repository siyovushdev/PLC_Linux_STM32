package presentation

import data.PlcProtocol
import domain.PlcNodeState
import domain.PlcStatusUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlcStatusController(
    private val scope: CoroutineScope
) {
    private var autoRefreshJob: Job? = null

    private val _statusUi = MutableStateFlow<PlcStatusUiState?>(null)
    val statusUi: StateFlow<PlcStatusUiState?> = _statusUi

    // --------- Автообновление статуса / нод ---------

    fun startAutoRefresh(intervalMs: Long = 1000L) {
        if (autoRefreshJob != null) return

        autoRefreshJob = scope.launch {
            while (isActive) {
                loadStatusOnce()
                delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    // --------- Одноразовое обновление ---------

    fun refreshStatusOnce() {
        scope.launch { loadStatusOnce() }
    }

    // --------- Внутренний запрос статуса ---------

    private suspend fun loadStatusOnce() {
        try {
            val data = withContext(Dispatchers.IO) {
                PlcProtocol.requestStatus()
            }
            _statusUi.value = data
        } catch (e: Exception) {
            val current = _statusUi.value
            if (current != null) {
                _statusUi.value = current.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Ошибка загрузки статуса"
                )
            } else {
                // если статуса ещё не было — создаём минимальный UI-state (если у тебя есть дефолт)
                // иначе просто оставляем null, но фиксируем ошибку отдельным способом
                // (можешь заменить на свой "empty" state если он есть)
            }
        }
    }

    // --------- Управление форсом ---------

    /**
     * Включить/выключить форс для дискретного выхода.
     * desired = true -> форс 1; false -> форс 0.
     */
    fun forceDigitalOutput(node: PlcNodeState, desired: Boolean) {
        scope.launch {
            val id = node.id // считаем, что id == nodeIndex
            val intVal = if (desired) 1 else 0

            val ok = withContext(Dispatchers.IO) {
                PlcProtocol.forceOutputInt(id, intVal, 0)
            }

            if (ok) refreshStatusOnce()
        }
    }

    /**
     * Снять форс на выходе.
     */
    fun releaseOutput(node: PlcNodeState) {
        scope.launch {
            val id = node.id

            val ok = withContext(Dispatchers.IO) {
                PlcProtocol.releaseOutput(id)
            }

            if (ok) refreshStatusOnce()
        }
    }
}
