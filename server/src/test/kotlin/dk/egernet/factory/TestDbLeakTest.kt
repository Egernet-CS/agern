package dk.egernet.factory

import com.zaxxer.hikari.HikariDataSource
import dk.egernet.factory.db.Db
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The test support gives its database connections back: every test gets its own database and pool on one embedded
 * PostgreSQL, which allows only so many clients, so a leaked pool makes some later test fail.
 */
class TestDbLeakTest {
    /** Polls until the client-connection count is at most [max] (a closed backend can linger briefly) and returns it. */
    private fun clientConnectionsSettled(max: Int): Int {
        val until = System.currentTimeMillis() + 5_000
        var n = TestDb.clientConnections()
        while (n > max && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            n = TestDb.clientConnections()
        }
        return n
    }

    @Test
    fun `closing a TestWorld gives its connections back`() {
        val connections = TestDb.clientConnections()
        val pools = TestDb.openPools()
        repeat(5) {
            val world = TestWorld(twoFactor = false)
            try {
                world.user("alice")
                assertTrue(TestDb.clientConnections() > connections, "an open world holds connections")
            } finally {
                world.close()
            }
        }
        assertEquals(pools, TestDb.openPools())
        val after = clientConnectionsSettled(connections)
        assertTrue(after <= connections, "client connections grew from $connections to $after")
    }

    @Test
    fun `close is idempotent and stopping the services alone keeps the pool open`() {
        val world = TestWorld(twoFactor = false)
        try {
            world.services.stop()
            assertEquals(1, world.services.db.queryOne("SELECT 1") { it.getInt(1) })
        } finally {
            world.close()
        }
        assertTrue((world.services.db.dataSource as HikariDataSource).isClosed)
        world.close()
    }

    @Test
    fun `TestDb refuses to open more pools than the limit and names who left them open`() {
        val opened = mutableListOf<Db>()
        try {
            while (TestDb.openPools() < TestDb.MAX_OPEN_POOLS) opened += TestDb.fresh()
            val e = assertFailsWith<IllegalStateException> { opened += TestDb.fresh() }
            assertTrue("TestDbLeakTest" in e.message.orEmpty(), "message names the creating class: ${e.message}")
            assertTrue("${TestDb.MAX_OPEN_POOLS} test database pools are still open" in e.message.orEmpty(), e.message)
        } finally {
            opened.forEach { (it.dataSource as java.io.Closeable).close() }
        }
        val db = TestDb.fresh()
        (db.dataSource as java.io.Closeable).close()
    }

    @Test
    fun `a failed background coroutine fails the test that owns the services, after the pool is closed`() {
        val pools = TestDb.openPools()
        val world = TestWorld(twoFactor = false)
        runBlocking { world.services.scope.launch { error("boom") }.join() }
        assertTrue(world.services.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive, "the scope survives the failure")
        val e = assertFailsWith<AssertionError> { world.close() }
        assertTrue("boom" in e.message.orEmpty(), e.message)
        assertEquals("boom", e.cause?.message)
        assertEquals(pools, TestDb.openPools())
        assertTrue((world.services.db.dataSource as HikariDataSource).isClosed)
    }

    @Test
    fun `a world without background failures closes without throwing`() {
        val world = TestWorld(twoFactor = false)
        runBlocking { world.services.scope.launch { world.user("alice") }.join() }
        world.close()
        assertTrue(world.backgroundFailures.isEmpty())
    }
}
