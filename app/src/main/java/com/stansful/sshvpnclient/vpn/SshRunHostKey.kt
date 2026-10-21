package com.stansful.sshvpnclient.vpn

/**
 * The host key fingerprint one SSH connection run checks the server against.
 *
 * - Before each reconnect it follows what is saved for the server ([onSavedFingerprintRead]): a
 *   fingerprint cleared in the editor while the run is up turns trust on first use back on from the
 *   next attempt, a replaced one is checked from then on.
 * - After a sign-in that trusted the key on first use it takes what ended up saved ([afterFirstUse]).
 *   A fingerprint saved meanwhile by someone else wins; if it names another key, the session must not
 *   carry traffic.
 * - A first-use key that could not be saved is still checked for the rest of the run, as long as
 *   nothing else gets saved for the server.
 *
 * Used by one connection loop at a time; not thread-safe.
 */
internal class SshRunHostKey(savedFingerprint: String?) {
    /** What the next attempt checks the server's key against; null trusts it on first use. */
    var fingerprint: String? = savedFingerprint.orNullIfBlank()
        private set

    /** A key trusted on first use this run that could not be saved. */
    private var unsavedFirstUse: String? = null

    /** [saved]: the server's fingerprint as stored now (not called when the server is gone or unreadable). */
    fun onSavedFingerprintRead(saved: String?) {
        val value = saved.orNullIfBlank()
        if (value != null) unsavedFirstUse = null
        fingerprint = value ?: unsavedFirstUse
    }

    /**
     * After a sign-in that trusted [hostKey] on first use and the attempt to pin it. [saved] is what the
     * server has stored afterwards: [hostKey]'s own fingerprint when the pin was written, a fingerprint
     * saved in the meantime, or null when nothing could be saved or read (the key is then checked for
     * this run only). Returns false when [saved] names a different key.
     */
    fun afterFirstUse(hostKey: SshHostKeyTrust.FirstUse, saved: String?): Boolean {
        val value = saved.orNullIfBlank()
        if (value == null) {
            unsavedFirstUse = hostKey.fingerprint
            fingerprint = hostKey.fingerprint
            return true
        }
        unsavedFirstUse = null
        fingerprint = value
        return hostKey.matches(value)
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
