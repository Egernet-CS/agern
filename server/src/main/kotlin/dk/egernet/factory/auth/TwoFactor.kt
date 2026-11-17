package dk.egernet.factory.auth

import dk.egernet.factory.LoginStepDto
import dk.egernet.factory.db.TwoFactorStore
import dk.egernet.factory.db.UserRecord
import dk.egernet.factory.db.UserStore
import dk.egernet.factory.locked
import dk.egernet.factory.security.Base32
import dk.egernet.factory.security.RecoveryCodes
import dk.egernet.factory.security.SecretBox
import dk.egernet.factory.security.Totp
import dk.egernet.factory.unprocessable
import java.time.Instant

/**
 * TOTP second factor: enrolment, verification with replay protection and lockout, recovery codes.
 * Secrets are encrypted with the master key (associated data `totp:<username>`); recovery codes are hashed.
 */
class TwoFactorService(
    private val users: UserStore,
    private val store: TwoFactorStore,
    private val box: SecretBox,
    private val nowSeconds: () -> Long = { Instant.now().epochSecond },
) {
    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MINUTES = 15L
        const val ISSUER = "Agern"
    }

    private fun ad(u: UserRecord) = "totp:${u.username}"

    fun startEnrolment(u: UserRecord): LoginStepDto {
        val secret = Totp.newSecret()
        val b32 = Base32.encode(secret)
        users.setTotpPending(u.id, box.encrypt(b32, ad(u)))
        return LoginStepDto(next = "enroll", secret = b32, otpauthUri = Totp.otpauthUri(ISSUER, u.username, b32))
    }

    private fun ensureNotLocked(u: UserRecord) {
        val until = u.totpLockedUntil
        if (until != null && until.isAfter(Instant.now())) {
            throw locked("Too many wrong codes. Two-factor sign-in is locked until $until.", until)
        }
    }

    private fun fail(u: UserRecord, remote: String?, reason: String): Nothing {
        store.audit(u.id, u.username, "totp_failed", reason, remote)
        val lockedUntil = users.recordTotpFailure(u.id, MAX_FAILURES, LOCK_MINUTES)
        if (lockedUntil != null) {
            store.audit(u.id, u.username, "totp_locked", "locked for $LOCK_MINUTES minutes", remote)
            throw locked("Too many wrong codes. Two-factor sign-in is locked for $LOCK_MINUTES minutes.", lockedUntil)
        }
        throw unprocessable("Invalid code", "invalid_code")
    }

    /** Second step of login. [code] is a TOTP code or a recovery code. */
    fun verifyLogin(u: UserRecord, code: String, remote: String?) {
        ensureNotLocked(u)
        val secretEnc = u.totpSecretEnc
        if (!u.totpEnabled || secretEnc == null) throw unprocessable("Two-factor authentication is not set up", "not_enrolled")
        if (RecoveryCodes.looksLikeRecoveryCode(code)) {
            if (store.consumeRecoveryCode(u.id, RecoveryCodes.hash(code))) {
                users.resetTotpFailures(u.id)
                store.audit(u.id, u.username, "recovery_used", null, remote)
                return
            }
            fail(u, remote, "bad recovery code")
        }
        val secret = Base32.decode(box.decrypt(secretEnc, ad(u)))
        val step = Totp.verify(secret, code, nowSeconds(), u.totpLastStep) ?: fail(u, remote, "bad totp code")
        if (!users.acceptTotpStep(u.id, step)) fail(u, remote, "replayed totp code")
    }

    /** Confirms the pending secret; returns the fresh recovery codes (shown once). */
    fun confirmEnrolment(u: UserRecord, code: String, remote: String?): List<String> {
        ensureNotLocked(u)
        val pending = u.totpPendingEnc ?: throw unprocessable("No enrolment in progress; log in again", "no_enrolment")
        val b32 = box.decrypt(pending, ad(u))
        val step = Totp.verify(Base32.decode(b32), code, nowSeconds(), null) ?: fail(u, remote, "bad enrolment code")
        users.confirmTotp(u.id, box.encrypt(b32, ad(u)), step)
        val codes = newRecoveryCodes(u)
        store.audit(u.id, u.username, "totp_enrolled", null, remote)
        return codes
    }

    fun regenerateRecoveryCodes(u: UserRecord, code: String, remote: String?): List<String> {
        ensureNotLocked(u)
        val secretEnc = u.totpSecretEnc ?: throw unprocessable("Two-factor authentication is not set up", "not_enrolled")
        val step = Totp.verify(Base32.decode(box.decrypt(secretEnc, ad(u))), code, nowSeconds(), u.totpLastStep)
            ?: fail(u, remote, "bad totp code (recovery regeneration)")
        if (!users.acceptTotpStep(u.id, step)) fail(u, remote, "replayed totp code")
        val codes = newRecoveryCodes(u)
        store.audit(u.id, u.username, "recovery_regenerated", null, remote)
        return codes
    }

    private fun newRecoveryCodes(u: UserRecord): List<String> {
        val codes = RecoveryCodes.generate(10)
        store.replaceRecoveryCodes(u.id, codes.map { RecoveryCodes.hash(it) })
        return codes
    }

    fun reset(admin: UserRecord, target: UserRecord, remote: String?) {
        users.resetTotp(target.id)
        store.deleteRecoveryCodes(target.id)
        store.audit(target.id, target.username, "totp_reset", "by ${admin.username}", remote)
    }
}
