package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.ZenTaskInfo
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class HealthResponse(val status: String, val version: String)

@Serializable
data class ErrorResponse(val error: String)

private val log = LoggerFactory.getLogger("ZenTask")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3100
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // Malformed input (bad percent-encoding, later bad JSON) is the client's fault: 400, no stack trace.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<URLDecodeException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal"))
        }
    }
    routing {
        get("/health") { call.respond(HealthResponse(status = "ok", version = ZenTaskInfo.version)) }
    }
}
