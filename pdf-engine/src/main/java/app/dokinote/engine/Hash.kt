package app.dokinote.engine

import java.security.MessageDigest

object Hash {
    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Fixed page geometry for reflowable documents (Office, EPUB...). Same in every worker, so pagination matches. */
object ReflowLayout {
    const val W = 595f
    const val H = 842f
    const val EM = 11f
}
