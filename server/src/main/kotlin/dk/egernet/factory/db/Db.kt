package dk.egernet.factory.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Thin JDBC helper around a HikariCP pool. All SQL is plain and parameterised. */
class Db(val dataSource: DataSource) {

    fun <T> tx(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val r = block(c)
            c.commit()
            r
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    fun <T> conn(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun update(sql: String, vararg params: Any?): Int = conn { c -> c.prepare(sql, params).use { it.executeUpdate() } }

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> = conn { c ->
        c.prepare(sql, params).use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }
    }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? = query(sql, *params, map = map).firstOrNull()

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        fun connect(url: String, user: String, password: String, poolSize: Int = 10): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                poolName = "agern"
            }
            val ds = HikariDataSource(cfg)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds)
        }
    }
}

/** Marker for a JSONB parameter. */
class JsonbParam(val json: String)

fun Connection.prepare(sql: String, params: Array<out Any?>): PreparedStatement {
    val ps = prepareStatement(sql)
    params.forEachIndexed { i, p -> ps.bind(i + 1, p) }
    return ps
}

fun PreparedStatement.bind(i: Int, p: Any?) {
    when (p) {
        null -> setNull(i, Types.NULL)
        is UUID -> setObject(i, p)
        is String -> setString(i, p)
        is Int -> setInt(i, p)
        is Long -> setLong(i, p)
        is Double -> setDouble(i, p)
        is Boolean -> setBoolean(i, p)
        is ByteArray -> setBytes(i, p)
        is Instant -> setTimestamp(i, Timestamp.from(p))
        is JsonbParam -> setObject(i, p.json, Types.OTHER)
        else -> throw IllegalArgumentException("Unsupported SQL parameter type: ${p::class}")
    }
}

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }
fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).let { if (wasNull()) null else it }
