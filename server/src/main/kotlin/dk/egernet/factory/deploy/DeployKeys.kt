package dk.egernet.factory.deploy

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair

/**
 * A freshly generated project key: the `authorized_keys` line, its fingerprint and the private key in OpenSSH format.
 * [privateKey] is meant to be encrypted at once and dropped; [toString] never shows it.
 */
class GeneratedDeployKey(val publicKey: String, val fingerprint: String, val privateKey: String) {
    override fun toString() = "GeneratedDeployKey($fingerprint)"
}

/**
 * A decrypted private key for one connection. Held only in memory, never written anywhere; [toString] never shows it.
 */
class DeployIdentity(private val openSsh: String, val fingerprint: String) {
    /** The key pair, parsed afresh for each connection. */
    fun keyPair(): KeyPair = DeployKeys.load(openSsh)
    override fun toString() = "DeployIdentity($fingerprint)"
}

/** Ed25519 key pairs for deploy targets (docs/DEPLOY.md "The key"), generated in the server process. */
object DeployKeys {
    /** Generates a new pair; the public key line ends with [comment] (`agern-deploy:<org>/<repo>`). */
    fun generate(comment: String): GeneratedDeployKey {
        val kp = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256)
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, comment, null, out)
        return GeneratedDeployKey("${PublicKeyEntry.toString(kp.public)} $comment", fingerprint(kp), String(out.toByteArray(), Charsets.US_ASCII))
    }

    fun fingerprint(kp: KeyPair): String = KeyUtils.getFingerPrint(BuiltinDigests.sha256, kp.public)

    /** The SecretBox associated data of [repo]'s stored private key (`deploy-key:<repo>`). */
    fun aad(repo: String) = "deploy-key:$repo"

    /**
     * [repo]'s decrypted key, in memory for one operation (deploy targets and sandbox VMs share it); null when the
     * project has none. Blocking (reads the database).
     */
    fun identity(keys: dk.egernet.factory.db.DeployKeyStore, box: dk.egernet.factory.security.SecretBox, repo: String): DeployIdentity? =
        keys.get(repo)?.let { DeployIdentity(box.decrypt(it.privateKeyEnc, aad(repo)), it.fingerprint) }

    /** Parses a private key written by [generate] (OpenSSH format, unencrypted). */
    fun load(openSsh: String): KeyPair =
        SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("deploy-key"), ByteArrayInputStream(openSsh.toByteArray(Charsets.US_ASCII)), null)
            ?.firstOrNull() ?: throw IllegalStateException("The stored deploy key could not be read")
}
