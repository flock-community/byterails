package fixtures.lib.util

object Timer {
    fun record(nanos: Long) {
        check(nanos >= 0)
    }
}

/** The body of an inline function is copied into every caller, together with its references. */
inline fun <T> timed(block: () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        Timer.record(System.nanoTime() - start)
    }
}
