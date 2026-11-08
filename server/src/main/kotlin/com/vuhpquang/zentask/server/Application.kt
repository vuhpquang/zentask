package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.ZenTaskInfo
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.server.auth.DEVICE_AUTH
import com.vuhpquang.zentask.server.auth.LoginLimiter
import com.vuhpquang.zentask.server.auth.PasswordCheck
import com.vuhpquang.zentask.server.auth.Tokens
import com.vuhpquang.zentask.server.auth.deviceBearer
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.server.sync.syncRoutes
import com.vuhpquang.zentask.sync.ApiError
import com.vuhpquang.zentask.sync.DeviceInfo
import com.vuhpquang.zentask.sync.LoginRequest
import com.vuhpquang.zentask.sync.LoginResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class HealthResponse(val status: String, val version: String)

private val log = LoggerFactory.getLogger("ZenTask")

fun main() {
    val config = ServerConfig.fromEnv()
    log.info("Starting ZenTask ${ZenTaskInfo.version} on ${config.host}:${config.port}")
    embeddedServer(Netty, port = config.port, host = config.host) { module(config) }.start(wait = true)
}

fun Application.module(config: ServerConfig = ServerConfig.fromEnv(), now: () -> Long = System::currentTimeMillis) {
    val db = ServerDb(config.dataDir.resolve("zentask.db"), now)
    monitor.subscribe(ApplicationStopped) { db.close() }
    val password = PasswordCheck(config.password)
    val limiter = LoginLimiter(now)

    install(ContentNegotiation) { json(ZenJson) }
    install(Authentication) { deviceBearer(db) }
    install(StatusPages) {
        // Malformed input (bad percent-encoding, bad JSON) is the client's fault: 400, no stack trace.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        }
        exception<URLDecodeException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal"))
        }
        // Bearer auth answers 401 with no body; give it the usual error body.
        status(HttpStatusCode.Unauthorized) { call, status -> call.respond(status, ApiError("unauthorized")) }
    }
    routing {
        get("/health") { call.respond(HealthResponse(status = "ok", version = ZenTaskInfo.version)) }
        // Public pages Google's OAuth consent screen links to (home, privacy policy, terms).
        publicPage("/", "index.html")
        publicPage("/privacy", "privacy.html")
        publicPage("/terms", "terms.html")

        route("/v1") {
            post("/auth/login") {
                val req = call.receive<LoginRequest>()
                val slot = limiter.reserve()
                if (slot == null) {
                    log.warn("Login refused: too many failed attempts")
                    return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("too_many_attempts"))
                }
                if (!password.matches(req.password)) {
                    log.warn("Login failed (wrong password)")
                    return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                }
                limiter.succeeded(slot)
                val token = Tokens.issue()
                val name = req.deviceName.trim().take(100).ifEmpty { "Unknown device" }
                val deviceId = db.createDevice(name, Tokens.hash(token))
                log.info("Device logged in: $name ($deviceId)")
                call.respond(LoginResponse(token = token, deviceId = deviceId))
            }
            authenticate(DEVICE_AUTH) {
                get("/devices") {
                    call.respond(db.listDevices().map { DeviceInfo(it.id, it.name, it.createdAt, it.lastSeenAt) })
                }
                syncRoutes(db, now)
                delete("/devices/{id}") {
                    if (db.revoke(call.parameters["id"].orEmpty())) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    }
                }
            }
        }
    }
}

private fun Route.publicPage(path: String, resource: String) {
    val html = HealthResponse::class.java.classLoader.getResource("public/$resource")?.readText()
        ?: error("missing resource public/$resource")
    get(path) { call.respondText(html, ContentType.Text.Html) }
}
