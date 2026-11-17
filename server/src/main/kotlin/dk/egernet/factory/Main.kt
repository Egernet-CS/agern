package dk.egernet.factory

import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    val log = LoggerFactory.getLogger("dk.egernet.factory")
    val cfg = try {
        Config.fromEnv()
    } catch (e: Exception) {
        System.err.println("Agern: configuration error: ${e.message}")
        exitProcess(2)
    }
    log.info("Agern — the software factory {} starting: {}", BuildInfo.version, cfg)
    if (!cfg.twoFactorRequired) log.warn("!!!!!!!! FACTORY_2FA=off: second factor disabled. Only for automated tests. !!!!!!!!")
    val services = Services.production(cfg)
    services.start()
    val server = embeddedServer(Netty, configure = {
        connector { host = cfg.host; port = cfg.port }
        // Agents reach MCP over loopback; make sure 127.0.0.1 is served even when bound to one LAN address.
        if (cfg.needsLoopbackListener) connector { host = "127.0.0.1"; port = cfg.port }
    }) { agernModule(services) }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Agern shutting down")
        services.stop()
        server.stop(1000, 5000)
    })
    log.info(
        "Agern listening on {}:{}{} (public URL {})", cfg.host, cfg.port,
        if (cfg.needsLoopbackListener) " and 127.0.0.1:${cfg.port} (for agents' MCP calls)" else "", cfg.publicUrl,
    )
    server.start(wait = true)
}
