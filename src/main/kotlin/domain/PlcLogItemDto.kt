package domain

data class PlcLogItemDto(
    val tsMs: Long,   // timestamp (ms)
    val code: Int,    // event code
    val a: Int,       // arg0
    val b: Int,       // arg1
)

data class PlcLogDumpDto(
    val total: Int,                 // всего записей в ring
    val from: Int,                  // с какого индекса реально отдали
    val count: Int,                 // сколько реально отдали
    val items: List<PlcLogItemDto>, // записи
)
