package ktorserver

import kotlinx.serialization.Serializable

@Serializable data class ForceReq(val nodeIndex: Int, val valueInt: Int, val holdMs: Int = 0)
@Serializable data class ReleaseReq(val nodeIndex: Int)

@Serializable data class MemReadReq(val type: String, val from: Int, val count: Int)
@Serializable data class MemWriteBoolReq(val from: Int, val values: List<Boolean>)
@Serializable data class MemWriteIntReq(val from: Int, val values: List<Int>)
@Serializable data class MemWriteRealReq(val from: Int, val values: List<Float>)

@Serializable data class UploadGraphReq(val graphJson: String) // если веб будет слать json графа
