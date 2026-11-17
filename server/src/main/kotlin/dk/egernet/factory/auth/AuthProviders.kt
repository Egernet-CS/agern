package dk.egernet.factory.auth

import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.RDN
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPURL
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.util.ssl.SSLUtil
import com.unboundid.util.ssl.TrustStoreTrustManager
import dk.egernet.factory.Config
import dk.egernet.factory.LdapConfig
import org.jvnet.libpam.PAM
import org.jvnet.libpam.PAMException
import org.slf4j.LoggerFactory
import java.security.MessageDigest

data class AuthResult(val username: String, val displayName: String)

/** A login provider. Returns null for "these credentials are not valid here" (try the next provider). */
interface AuthProvider {
    val name: String
    fun authenticate(username: String, password: String): AuthResult?
}

private val log = LoggerFactory.getLogger("dk.egernet.factory.auth")

/** Usernames are restricted to a conservative charset before they reach PAM or LDAP. */
val USERNAME_RE = Regex("^(?!\\.{1,2}$)[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$")

class AuthService(private val providers: List<AuthProvider>) {
    val names: List<String> get() = providers.map { it.name }

    fun authenticate(username: String, password: String): AuthResult? {
        if (!USERNAME_RE.matches(username) || password.isEmpty() || password.length > 1024) return null
        for (p in providers) {
            try {
                p.authenticate(username, password)?.let { return it }
            } catch (e: Exception) {
                log.warn("Auth provider {} failed for user {}: {}", p.name, username, e.javaClass.simpleName)
            } catch (e: LinkageError) {
                log.error("Auth provider {} is unusable on this platform: {}", p.name, e.message)
            }
        }
        return null
    }

    companion object {
        fun fromConfig(cfg: Config): AuthService = AuthService(cfg.authProviders.map {
            when (it) {
                "dev" -> {
                    log.warn("!!!!!!!! DEV LOGIN PROVIDER ENABLED (FACTORY_AUTH contains 'dev'). Static users: {}. NEVER use this in production. !!!!!!!!", cfg.devUsers.keys)
                    DevAuthProvider(cfg.devUsers)
                }
                "ldap" -> LdapAuthProvider(cfg.ldap)
                "os" -> OsAuthProvider(cfg.pamService)
                else -> error("unknown provider $it")
            }
        })
    }
}

class DevAuthProvider(private val users: Map<String, String>) : AuthProvider {
    override val name = "dev"
    override fun authenticate(username: String, password: String): AuthResult? {
        val expected = users[username] ?: return null
        return if (MessageDigest.isEqual(expected.toByteArray(), password.toByteArray())) AuthResult(username, username) else null
    }
}

/**
 * LDAP bind. Either a DN template (`uid={0},ou=people,dc=x`) or search-then-bind
 * (optionally as FACTORY_LDAP_BIND_DN) under FACTORY_LDAP_BASE_DN with FACTORY_LDAP_USER_FILTER.
 */
class LdapAuthProvider(private val cfg: LdapConfig) : AuthProvider {
    override val name = "ldap"

    init {
        require(cfg.url != null) { "FACTORY_LDAP_URL is required for the ldap provider" }
        require(cfg.userDnTemplate != null || cfg.baseDn != null) {
            "Either FACTORY_LDAP_USER_DN_TEMPLATE or FACTORY_LDAP_BASE_DN is required for the ldap provider"
        }
    }

    private fun connect(): LDAPConnection {
        val url = LDAPURL(cfg.url)
        return if (url.scheme.equals("ldaps", ignoreCase = true)) {
            val ssl = SSLUtil(TrustStoreTrustManager(System.getProperty("java.home") + "/lib/security/cacerts"))
            LDAPConnection(ssl.createSSLSocketFactory(), url.host, url.port)
        } else {
            LDAPConnection(url.host, url.port)
        }
    }

    override fun authenticate(username: String, password: String): AuthResult? {
        if (password.isEmpty()) return null // an empty password would be an anonymous bind
        connect().use { conn ->
            val userDn: String
            var displayName: String? = null
            if (cfg.userDnTemplate != null) {
                userDn = cfg.userDnTemplate.replace("{0}", escapeDnValue(username))
            } else {
                if (cfg.bindDn != null) conn.bind(cfg.bindDn, cfg.bindPassword ?: "")
                val filter = Filter.create(cfg.userFilter.replace("{0}", Filter.encodeValue(username)))
                val res = conn.search(cfg.baseDn, SearchScope.SUB, filter, "displayName", "cn")
                if (res.entryCount != 1) return null
                val e = res.searchEntries.first()
                userDn = e.dn
                displayName = e.getAttributeValue("displayName") ?: e.getAttributeValue("cn")
            }
            try {
                conn.bind(userDn, password)
            } catch (e: LDAPException) {
                if (e.resultCode == ResultCode.INVALID_CREDENTIALS) return null
                throw e
            }
            if (displayName == null) {
                displayName = runCatching {
                    conn.getEntry(userDn, "displayName", "cn")?.let { it.getAttributeValue("displayName") ?: it.getAttributeValue("cn") }
                }.getOrNull()
            }
            return AuthResult(username, displayName ?: username)
        }
    }

    /** RFC 4514 escaping of an attribute value, via the SDK's RDN encoder. */
    private fun escapeDnValue(v: String): String = RDN("x", v).toString().substringAfter('=')
}

/**
 * Login against an account on the host via PAM (libpam4j over JNA). The password is handed to PAM
 * in-process and never appears in a process argument list.
 * Default PAM service: `chkpasswd` on macOS, `login` elsewhere (override with FACTORY_PAM_SERVICE).
 */
class OsAuthProvider(serviceOverride: String?) : AuthProvider {
    override val name = "os"
    private val service = serviceOverride
        ?: if (System.getProperty("os.name").lowercase().contains("mac")) "chkpasswd" else "login"

    override fun authenticate(username: String, password: String): AuthResult? {
        val pam = PAM(service)
        return try {
            val u = pam.authenticate(username, password)
            val gecos = runCatching { u.gecos }.getOrNull()?.split(',')?.firstOrNull()?.takeIf { it.isNotBlank() }
            AuthResult(u.userName, gecos ?: u.userName)
        } catch (e: PAMException) {
            null
        } finally {
            pam.dispose()
        }
    }
}
