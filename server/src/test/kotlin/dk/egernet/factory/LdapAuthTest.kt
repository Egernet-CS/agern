package dk.egernet.factory

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import dk.egernet.factory.auth.AuthService
import dk.egernet.factory.auth.LdapAuthProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LdapAuthTest {
    private val server = InMemoryDirectoryServer(
        InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
            setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0))
            addAdditionalBindCredentials("cn=svc,dc=example,dc=com", "svc-pw")
        },
    ).apply {
        add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example")
        add("dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people")
        add("dn: uid=alice,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: alice", "cn: Alice A", "sn: A",
            "displayName: Alice Andersen", "userPassword: secret")
        startListening()
    }
    private val url = "ldap://127.0.0.1:${server.listenPort}"

    @AfterTest fun tearDown() = server.shutDown(true)

    @Test
    fun `dn template bind`() {
        val p = LdapAuthProvider(LdapConfig(url = url, userDnTemplate = "uid={0},ou=people,dc=example,dc=com"))
        assertEquals(dk.egernet.factory.auth.AuthResult("alice", "Alice Andersen"), p.authenticate("alice", "secret"))
        assertNull(p.authenticate("alice", "wrong"))
        assertNull(p.authenticate("alice", ""), "empty password must not become an anonymous bind")
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
}
