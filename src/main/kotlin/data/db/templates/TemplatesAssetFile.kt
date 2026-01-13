package data.db.templates

import domain.*


// JSON models
data class TemplatesAssetFile(
    val version: Int,
    val author: String,
    val createdAt: String,
    val templates: List<TemplateAsset>
)

data class TemplateAsset(
    val id: String,
    val name: String,
    val category: String,
    val level: String,
    val description: TemplateDescriptionAsset,
    val graph: GraphAsset
)

data class TemplateDescriptionAsset(
    val short: String,
    val long: List<String>
)

data class GraphAsset(
    val cycleMs: Int,
    val nodes: List<GraphNodeAsset>
)

data class GraphNodeAsset(
    val id: Int,
    val type: String,
    val inA: Int,
    val inB: Int,
    val valueType: String,
    val paramInt: Int,
    val paramFloat: Float,
    val paramMs: Int,
    val flags: Int
)

// ---- mapping ----

fun GraphAsset.toPlcGraphDef(): PlcGraphDef =
    PlcGraphDef(
        cycleMs = cycleMs,
        nodes = nodes.map { it.toPlcGraphNodeDef() }
    )

fun GraphNodeAsset.toPlcGraphNodeDef(): PlcGraphNodeDef =
    PlcGraphNodeDef(
        id = id,
        type = try {
            if (type.isDigitsOnlyJvm()) PlcNodeType.get(type.toInt())
            else PlcNodeType.valueOf(type)
        } catch (_: Throwable) {
            PlcNodeType.valueOf(type)
        },
        valueType = try {
            if (valueType.isDigitsOnlyJvm()) valueType.toInt()
            else parseValueType(valueType)
        } catch (_: Throwable) {
            parseValueType(valueType)
        },
        inA = inA,
        inB = inB,
        paramInt = paramInt,
        paramFloat = paramFloat,
        paramMs = paramMs,
        flags = flags
    )

private fun String.isDigitsOnlyJvm(): Boolean =
    isNotEmpty() && all { it in '0'..'9' }

private fun parseValueType(s: String): Int =
    when (s.uppercase()) {
        "BOOL"  -> 0
        "INT"   -> 1
        "FLOAT" -> 2
        else    -> 0
    }
