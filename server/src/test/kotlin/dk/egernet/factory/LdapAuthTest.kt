package dk.egernet.factory

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedCompareRequest
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindResult
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import dk.egernet.factory.auth.AuthService
import dk.egernet.factory.auth.LdapAuthProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LdapAuthTest {
    /** Records who is bound on each connection when a compare arrives, and can refuse compares on chosen groups. */
    private class Recorder : InMemoryOperationInterceptor() {
        val boundAs = ConcurrentHashMap<Long, String>()
        /** (group DN, bind DN of the connection at that moment) */
        val compares = CopyOnWriteArrayList<Pair<String, String>>()
        val refused = ConcurrentHashMap.newKeySet<String>()

        override fun processSimpleBindResult(r: InMemoryInterceptedSimpleBindResult) {
            if (r.result.resultCode == ResultCode.SUCCESS) boundAs[r.connectionID] = r.request.bindDN
        }

        override fun processCompareRequest(r: InMemoryInterceptedCompareRequest) {
            val dn = r.request.dn
            compares += dn to (boundAs[r.connectionID] ?: "")
            if (refused.any { DN(it) == DN(dn) }) throw LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS)
        }
    }

    private val recorder = Recorder()
    private val server = InMemoryDirectoryServer(
        InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
            addAdditionalBindCredentials("cn=svc,dc=example,dc=com", "svc-pw")
            addInMemoryOperationInterceptor(recorder)
        },
    ).apply {
        add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example")
        add("dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people")
        add("dn: uid=alice,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: alice", "cn: Alice A", "sn: A",
            "displayName: Alice Andersen", "userPassword: secret")
        add("dn: uid=bob,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: bob", "cn: Bob B", "sn: B",
            "userPassword: bobpw")
        add("dn: ou=groups,dc=example,dc=com", "objectClass: organizationalUnit", "ou: groups")
        add("dn: cn=agern,ou=groups,dc=example,dc=com", "objectClass: groupOfNames", "cn: agern",
            "member: uid=alice,ou=people,dc=example,dc=com")
        add("dn: cn=others,ou=groups,dc=example,dc=com", "objectClass: groupOfNames", "cn: others",
            "member: uid=bob,ou=people,dc=example,dc=com")
        add("dn: cn=unique,ou=groups,dc=example,dc=com", "objectClass: groupOfUniqueNames", "cn: unique",
            "uniqueMember: uid=alice,ou=people,dc=example,dc=com")
        startListening()
    }
    private val url = "ldap://127.0.0.1:${server.listenPort}"
    private val template = "uid={0},ou=people,dc=example,dc=com"
    private val agern = "cn=agern,ou=groups,dc=example,dc=com"
    private val aliceDn = "uid=alice,ou=people,dc=example,dc=com"

    @AfterTest fun tearDown() = server.shutDown(true)

    private fun captureAuthLog(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("dk.egernet.factory.auth") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try { block() } finally { logger.detachAppender(appender) }
        return appender.list.filter { it.level.toString() == "WARN" }.map { it.formattedMessage }
    }

    @Test
    fun `dn template bind`() {
        val p = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template))
        assertEquals(dk.egernet.factory.auth.AuthResult("alice", "Alice Andersen"), p.authenticate("alice", "secret"))
        assertNull(p.authenticate("alice", "wrong"))
        assertNull(p.authenticate("alice", ""), "empty password must not become an anonymous bind")
        assertTrue(recorder.compares.isEmpty(), "no group configured, no compare")
    }

    @Test
    fun `search then bind`() {
        val p = LdapAuthProvider(LdapConfig(url = url, baseDn = "ou=people,dc=example,dc=com", userFilter = "(uid={0})",
            bindDn = "cn=svc,dc=example,dc=com", bindPassword = "svc-pw"))
        assertEquals("Alice Andersen", p.authenticate("alice", "secret")?.displayName)
        assertNull(p.authenticate("alice", "wrong"))
        assertNull(p.authenticate("nobody", "secret"))
        // Filter injection attempts are escaped (and rejected earlier by the username rule in AuthService).
        assertNull(p.authenticate("*)(uid=*", "secret"))
        assertNull(AuthService(listOf(p)).authenticate("*", "secret"))
    }

    @Test
    fun `group check in dn template mode`() {
        val p = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template, requiredGroups = listOf(agern)))
        assertEquals(dk.egernet.factory.auth.AuthResult("alice", "Alice Andersen"), p.authenticate("alice", "secret"))
        assertEquals(listOf(agern to aliceDn), recorder.compares)
        val warnings = captureAuthLog { assertNull(p.authenticate("bob", "bobpw")) }
        assertEquals(listOf("ldap: bob is not in $agern"), warnings)

        recorder.compares.clear()
        assertNull(p.authenticate("alice", "wrong"))
        assertNull(p.authenticate("bob", "wrong"))
        assertTrue(recorder.compares.isEmpty(), "a wrong password must not reach the group check")
    }

    @Test
    fun `group check in search mode runs as the user, not the service account`() {
        val p = LdapAuthProvider(LdapConfig(url = url, baseDn = "ou=people,dc=example,dc=com",
            bindDn = "cn=svc,dc=example,dc=com", bindPassword = "svc-pw", requiredGroups = listOf(agern)))
        assertEquals("Alice Andersen", p.authenticate("alice", "secret")?.displayName)
        assertNull(p.authenticate("bob", "bobpw"))
        assertEquals(listOf(agern to aliceDn, agern to "uid=bob,ou=people,dc=example,dc=com"), recorder.compares)
    }

    @Test
    fun `unreadable group refuses the login without throwing`() {
        val missing = "cn=missing,ou=groups,dc=example,dc=com"
        val p1 = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template, requiredGroups = listOf(missing)))
        val warnings = captureAuthLog { assertNull(p1.authenticate("alice", "secret")) }
        assertEquals(listOf("ldap: cannot check $missing for alice: 32 (no such object)", "ldap: alice is not in $missing"), warnings)

        recorder.refused += agern
        val p2 = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template, requiredGroups = listOf(agern)))
        assertNull(p2.authenticate("alice", "secret"))
        assertNull(AuthService(listOf(p2)).authenticate("alice", "secret"))
    }

    @Test
    fun `any of several groups admits`() {
        val others = "cn=others,ou=groups,dc=example,dc=com"
        recorder.refused += others
        val p = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template,
            requiredGroups = listOf("cn=missing,ou=groups,dc=example,dc=com", others, agern)))
        assertEquals("Alice Andersen", p.authenticate("alice", "secret")?.displayName)
        // bob is listed in 'others', but that group cannot be read: fail closed.
        val warnings = captureAuthLog { assertNull(p.authenticate("bob", "bobpw")) }
        assertEquals("ldap: bob is not in any of cn=missing,ou=groups,dc=example,dc=com; $others; $agern", warnings.last())

        recorder.compares.clear()
        val first = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template, requiredGroups = listOf(agern, others)))
        assertNotNull(first.authenticate("alice", "secret"))
        assertEquals(listOf(agern), recorder.compares.map { it.first }, "later groups are not asked after a match")
    }

    @Test
    fun `uniqueMember variant`() {
        val p = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = template,
            requiredGroups = listOf("cn=unique,ou=groups,dc=example,dc=com"), groupMemberAttribute = "uniqueMember"))
        assertNotNull(p.authenticate("alice", "secret"))
        assertNull(p.authenticate("bob", "bobpw"))
    }

    @Test
    fun `memberUid variant compares the login name`() {
        // posixGroup/memberUid are not in the SDK's bundled schema, so this directory runs without one.
        val posix = InMemoryDirectoryServer(InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
            setSchema(null)
        }).apply {
            add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example")
            add("dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people")
            add("dn: uid=alice,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: alice", "cn: Alice A", "userPassword: secret")
            add("dn: uid=bob,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: bob", "cn: Bob B", "userPassword: bobpw")
            add("dn: ou=groups,dc=example,dc=com", "objectClass: organizationalUnit", "ou: groups")
            add("dn: cn=agern,ou=groups,dc=example,dc=com", "objectClass: posixGroup", "cn: agern", "gidNumber: 5000", "memberUid: alice")
            startListening()
        }
        try {
            val p = LdapAuthProvider(LdapConfig(url = "ldap://127.0.0.1:${posix.listenPort}", userDnTemplate = template,
                requiredGroups = listOf(agern), groupMemberAttribute = "memberUid", groupMemberValue = "uid"))
            assertEquals("Alice A", p.authenticate("alice", "secret")?.displayName)
            assertNull(p.authenticate("bob", "bobpw"))
        } finally {
            posix.shutDown(true)
        }
    }

    @Test
    fun `invalid group settings fail provider construction`() {
        fun build(cfg: LdapConfig) = LdapAuthProvider(cfg.copy(url = url, userDnTemplate = template))
        val bad = listOf(
            LdapConfig(requiredGroups = listOf("not a dn")) to "FACTORY_LDAP_REQUIRED_GROUP",
            LdapConfig(requiredGroups = listOf("")) to "FACTORY_LDAP_REQUIRED_GROUP",
            LdapConfig(groupMemberValue = "email") to "FACTORY_LDAP_GROUP_MEMBER_VALUE",
            LdapConfig(groupMemberAttribute = "mem ber") to "FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE",
        )
        for ((cfg, variable) in bad) {
            val e = assertFailsWith<IllegalArgumentException> { build(cfg) }
            assertTrue(e.message!!.contains(variable), e.message)
        }
        build(LdapConfig(requiredGroups = listOf(agern), groupMemberAttribute = "uniqueMember", groupMemberValue = "uid"))
    }

    @Test
    fun `config parses the group settings`() {
        val base = mapOf("FACTORY_MASTER_KEY" to java.util.Base64.getEncoder().encodeToString(ByteArray(32)),
            "FACTORY_GITHUB_ORG" to "acme")
        val d = Config.fromEnv(base).ldap
        assertEquals(emptyList(), d.requiredGroups)
        assertEquals("member", d.groupMemberAttribute)
        assertEquals("dn", d.groupMemberValue)
        val c = Config.fromEnv(base + mapOf("FACTORY_LDAP_REQUIRED_GROUP" to "cn=a,dc=x ; cn=b,dc=x;",
            "FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE" to "memberUid", "FACTORY_LDAP_GROUP_MEMBER_VALUE" to "UID")).ldap
        assertEquals(listOf("cn=a,dc=x", "cn=b,dc=x"), c.requiredGroups)
        assertEquals("memberUid", c.groupMemberAttribute)
        assertEquals("uid", c.groupMemberValue)
        assertTrue(c.toString().contains("requiredGroups=[cn=a,dc=x, cn=b,dc=x]"))
    }

    @Test
    fun `a refused non-member gets exactly the wrong-password answer`() {
        val world = TestWorld(tweak = {
            it.copy(authProviders = listOf("ldap"), ldap = LdapConfig(url = url, userDnTemplate = template, requiredGroups = listOf(agern)))
        })
        try {
            testApplication {
                application { agernModule(world.services) }
                val c = createClient {}
                suspend fun login(u: String, pw: String) = c.post("/api/auth/login") {
                    header("X-Factory-Request", "1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"$u","password":"$pw"}""")
                }
                val wrong = login("bob", "nope")
                val refused = login("bob", "bobpw")
                assertEquals(401, wrong.status.value)
                assertEquals(wrong.status, refused.status)
                val body = refused.bodyAsText()
                assertEquals(wrong.bodyAsText(), body)
                assertTrue(body.contains("invalid_credentials") && body.contains("Wrong username or password"), body)
                assertFalse(body.contains("agern,ou=groups") || body.contains("cn=agern"), body)
                assertEquals(200, login("alice", "secret").status.value)
            }
        } finally {
            world.close()
        }
    }
}
