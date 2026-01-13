package presentation

import data.PlcProtocol
import domain.MemType
import helper.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

data class PlcMemoryUiState(
    val type: MemType = MemType.BOOL,
    val page: Int = 0,
    val pageSize: Int = 64,
    val totalCount: Int = 0,
    val fromIndex: Int = 0,
    val values: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
) {
    val totalPages: Int = max(1, ceil(totalCount / pageSize.toDouble()).toInt())
}

/**
 * JVM/CLI replacement for PlcMemoryViewModel.
 * - No Android ViewModel
 * - Uses provided CoroutineScope (inject via Koin)
 */
class PlcMemoryController(
    private val scope: CoroutineScope
) {
    private val _ui = MutableStateFlow(PlcMemoryUiState())
    val ui: StateFlow<PlcMemoryUiState> = _ui

    fun start() {
        // аналог init { reload() }
        reload()
    }

    fun setType(t: MemType) {
        _ui.value = _ui.value.copy(type = t, page = 0, error = null)
        reload()
    }

    fun setPageSize(n: Int) {
        _ui.value = _ui.value.copy(pageSize = n, page = 0, error = null)
        reload()
    }

    fun nextPage() {
        val s = _ui.value
        if (s.page + 1 >= s.totalPages) return
        _ui.value = s.copy(page = s.page + 1, error = null)
        reload()
    }

    fun prevPage() {
        val s = _ui.value
        if (s.page <= 0) return
        _ui.value = s.copy(page = s.page - 1, error = null)
        reload()
    }

    fun reload() {
        scope.launch {
            val s0 = _ui.value
            _ui.value = s0.copy(loading = true, error = null)

            try {
                // В CLI лучше увести сеть/протокол на IO
                val (total, from, values) = withContext(Dispatchers.IO) {
                    // 1) totalCount через status/memInfo
                    val status = PlcProtocol.requestMemInfo()
                    val total = when (s0.type) {
                        MemType.BOOL -> status.boolCount
                        MemType.INT -> status.intCount
                        MemType.REAL -> status.realCount
                    }

                    val from = s0.page * s0.pageSize
                    val count = (total - from).coerceIn(0, s0.pageSize)

                    val values = when (s0.type) {
                        MemType.BOOL -> PlcProtocol.memReadBool(from, count).map { it.toString() }
                        MemType.INT -> PlcProtocol.memReadInt(from, count).map { it.toString() }
                        MemType.REAL -> PlcProtocol.memReadReal(from, count).map { it.toString() }
                    }

                    Triple(total, from, values)
                }

                Log.d("PLC.MEM", "mem_read ${s0.type} from=$from size=${values.size}")

                _ui.value = s0.copy(
                    totalCount = total,
                    fromIndex = from,
                    values = values,
                    loading = false,
                    error = null
                )
            } catch (t: Throwable) {
                _ui.value = s0.copy(loading = false, error = t.message ?: "Ошибка")
            }
        }
    }

    fun writeSingle(type: MemType, index: Int, raw: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (type) {
                        MemType.BOOL -> {
                            val v = raw.equals("true", true) || raw == "1"
                            PlcProtocol.memWriteBool(index, listOf(v))
                        }
                        MemType.INT -> {
                            val v = raw.trim().toInt()
                            PlcProtocol.memWriteInt(index, listOf(v))
                        }
                        MemType.REAL -> {
                            val v = raw.trim().replace(',', '.').toFloat()
                            PlcProtocol.memWriteReal(index, listOf(v))
                        }
                    }
                }
                reload()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(error = t.message ?: "Ошибка записи")
            }
        }
    }

    fun resetAllMem() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PlcProtocol.memReset()
                }
                reload()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(error = t.message ?: "Ошибка сброса")
            }
        }
    }
}
