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
    }
}
