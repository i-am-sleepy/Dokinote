package app.dokinote.office.lok

/** Messenger protocol between the UI process and the `:lok` engine process. Plain constants only: the UI process must never touch LibreOfficeKit classes (loading them loads the 196 MB native library). */
object LokProtocol {
    const val MSG_RUN = 1   // UI -> engine: bundle with KEY_MODE, KEY_IN, KEY_OUT, KEY_SAL, KEY_DOC
    const val MSG_LOG = 2   // engine -> UI: KEY_TEXT
    const val MSG_DONE = 3  // engine -> UI: KEY_MODE, KEY_OK, KEY_TEXT
    const val MSG_KILL = 4  // UI -> engine: exit the process (unloads LibreOffice)

    const val KEY_MODE = "mode"
    const val KEY_IN = "in"
    const val KEY_OUT = "out"
    const val KEY_SAL = "sal"
    const val KEY_DOC = "doc"
    const val KEY_TEXT = "text"
    const val KEY_OK = "ok"

    const val MODE_BENCH = "bench"
    const val MODE_COLD = "cold"
    const val MODE_TYPING = "typing"
}
