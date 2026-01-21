package helper

object Log {
    fun d(tag: String, msg: String) {
//        println("$tag: $msg")
    }

    fun w(tag: String, msg: String) {
//        println("$tag: $msg")
    }

    fun i(tag: String, msg: String) {
//        println("$tag: $msg")
    }

    fun e(tag: String, msg: String, e: Exception?) {
        System.err.println("$tag: $msg | e: ${e?.message}")
    }

    fun e(tag: String, msg: String) {
        System.err.println("$tag: $msg")
    }
}