package tether.cli

/** A bidirectional NDJSON channel to one claude process. */
interface CliConnection {
    /** Begin delivering stdout lines and the exit notification. Callbacks may run on any thread. */
    fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit)
    fun send(line: String)
    fun stop()

    companion object {
        /** The exit code reported when claude could not be started at all; the tail is then the reason. */
        const val LAUNCH_FAILED = -1

        /**
         * What a chat says when the agent [name]'s process ended with [exitCode] (null when unknown): why it could not
         * be launched, or its exit code followed by the last 2000 characters of [stderrTail].
         */
        fun exitNotice(name: String, exitCode: Int?, stderrTail: String): String {
            val detail = stderrTail.trim().takeLast(2000)
            if (exitCode == LAUNCH_FAILED) return if (detail.isEmpty()) "$name could not be started." else "Couldn't start $name: $detail"
            val head = "$name exited (code ${exitCode ?: "unknown"})."
            return if (detail.isEmpty()) head else "$head\n$detail"
        }
    }
}
