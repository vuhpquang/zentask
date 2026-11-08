package com.vuhpquang.zentask.server.calendar

import com.vuhpquang.zentask.server.auth.DevicePrincipal
import com.vuhpquang.zentask.sync.ApiError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ConnectUrl(val url: String)

@Serializable
data class IcalRequest(val name: String, val url: String)

@Serializable
data class IcalAdded(val accountId: String)

private val log = LoggerFactory.getLogger("ZenTask.Calendar")

/** Authenticated calendar routes, mounted under `/v1` (full-scope devices only). */
fun Route.calendarRoutes(service: CalendarService) {
    post("/calendar/google/start") {
        val device = call.principal<DevicePrincipal>()!!.deviceId
        when (val r = service.start(device)) {
            is CalendarService.StartResult.Url -> call.respond(ConnectUrl(r.url))
            is CalendarService.StartResult.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError(r.code))
        }
    }
    post("/calendar/ical") {
        val req = call.receive<IcalRequest>()
        try {
            call.respond(IcalAdded(service.addIcal(req.name, req.url.trim())))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad_request"))
        }
    }
    post("/calendar/sync") {
        service.syncAll()
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/calendar/accounts/{id}") {
        if (service.remove(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
    }
}

/** Public: Google redirects the browser here with `code` and `state`. Answers a small HTML page. */
fun Route.calendarCallback(service: CalendarService) {
    get(CalendarService.CALLBACK_PATH) {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val denied = call.request.queryParameters["error"]
        if (denied != null || code == null || state == null) {
            return@get call.respondText(page("Not connected", "Google didn't grant access. You can close this tab and try again from ZenTask."), ContentType.Text.Html, HttpStatusCode.BadRequest)
        }
        try {
            val email = service.callback(code, state)
            call.respondText(page("Connected", "${escape(email)} is connected. Its calendars appear in ZenTask after the next sync. You can close this tab."), ContentType.Text.Html)
        } catch (e: IllegalArgumentException) {
            call.respondText(page("Not connected", escape(e.message ?: "Something went wrong.")), ContentType.Text.Html, HttpStatusCode.BadRequest)
        } catch (e: Exception) {
            log.warn("Calendar callback failed: {}", e.message)
            call.respondText(page("Not connected", "Google could not be reached. Try again from ZenTask in a moment."), ContentType.Text.Html, HttpStatusCode.BadGateway)
        }
    }
}

private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun page(title: String, message: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZenTask — $title</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8f7;--fg:#1d2a27;--accent:#288a79}
@media (prefers-color-scheme:dark){:root{--bg:#141a19;--fg:#e6efec;--accent:#52c2a6}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--fg);font:16px/1.5 -apple-system,system-ui,sans-serif}
main{max-width:420px;padding:32px;text-align:center}h1{color:var(--accent);font-size:24px;margin:0 0 8px}
</style></head><body><main><h1>$title</h1><p>$message</p></main></body></html>"""
