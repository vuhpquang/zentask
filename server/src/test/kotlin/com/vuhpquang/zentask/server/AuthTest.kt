package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.sync.DeviceInfo
import com.vuhpquang.zentask.sync.LoginResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun loginWithRightPasswordReturnsWorkingToken() = testApplication {
        application { module(testConfig()) }
        val res = client.login(deviceName = "MacBook")
        assertEquals(HttpStatusCode.OK, res.status)
        val login = ZenJson.decodeFromString(LoginResponse.serializer(), res.bodyAsText())
        assertTrue(login.token.length >= 40)
        val devices = client.get("/v1/devices") { bearerAuth(login.token) }
        assertEquals(HttpStatusCode.OK, devices.status)
        val list = ZenJson.decodeFromString(ListSerializer(DeviceInfo.serializer()), devices.bodyAsText())
        assertEquals(listOf("MacBook"), list.map { it.name })
        assertEquals(login.deviceId, list.single().id)
    }

    @Test
    fun wrongPasswordIs401() = testApplication {
        application { module(testConfig()) }
        val res = client.login(password = "nope")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("""{"error":"unauthorized"}""", res.bodyAsText())
    }

    @Test
    fun sixthFailureWithin15MinutesIs429() = testApplication {
        var now = 1_000_000L
        application { module(testConfig(), now = { now }) }
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login(password = "bad").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login(password = "bad").status)
        val blocked = client.login()
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, "correct password is still blocked inside the window")
        assertEquals("""{"error":"too_many_attempts"}""", blocked.bodyAsText())
        now += 15 * 60 * 1000 + 1
        assertEquals(HttpStatusCode.OK, client.login().status)
    }

    @Test
    fun parallelWrongPasswordsCannotExceedTheLimit() = testApplication {
        application { module(testConfig()) }
        startApplication()
        val statuses = coroutineScope {
            (1..20).map { async { client.login(password = "bad-$it").status } }.awaitAll()
        }
        assertEquals(5, statuses.count { it == HttpStatusCode.Unauthorized }, "statuses: $statuses")
        assertEquals(15, statuses.count { it == HttpStatusCode.TooManyRequests })
    }

    @Test
    fun missingOrGarbageBearerIs401() = testApplication {
        application { module(testConfig()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices") { bearerAuth("garbage") }.status)
        val malformed = client.get("/v1/devices") { header(HttpHeaders.Authorization, "Basic Zm9vOmJhcg==") }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        assertEquals("""{"error":"unauthorized"}""", malformed.bodyAsText())
    }

    @Test
    fun revokedTokenIs401() = testApplication {
        application { module(testConfig()) }
        val keep = client.loginToken("keep")
        val revoke = ZenJson.decodeFromString(LoginResponse.serializer(), client.login(deviceName = "old").bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/devices/${revoke.deviceId}") { bearerAuth(keep) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/devices") { bearerAuth(revoke.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/devices") { bearerAuth(keep) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/devices/unknown") { bearerAuth(keep) }.status)
        val names = ZenJson.decodeFromString(
            ListSerializer(DeviceInfo.serializer()),
            client.get("/v1/devices") { bearerAuth(keep) }.bodyAsText(),
        ).map { it.name }
        assertEquals(listOf("keep"), names)
    }

    @Test
    fun tokensAreStoredHashed() = testApplication {
        val dir = Files.createTempDirectory("zentask-hash")
        application { module(testConfig(dir)) }
        val token = client.loginToken()
        client.get("/v1/devices") { bearerAuth(token) }
        val bytes = Files.list(dir).toList().flatMap { it.readBytes().toList() }.toByteArray()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun everyV1RouteExceptLoginNeedsAuth() = testApplication {
        lateinit var routes: List<Pair<HttpMethod, String>>
        application {
            module(testConfig())
            routes = plugin(RoutingRoot).getAllRoutes().mapNotNull { route ->
                val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
                val path = route.parent.toString().replace(Regex("""\{[^}]+\}"""), "x")
                    .replace(Regex("""/\([^)]*\)"""), "")
                method to path
            }
        }
        startApplication()
        val v1 = routes.filter { it.second.startsWith("/v1") }
        assertTrue(v1.size >= 3, "found routes: $routes")
        for ((method, path) in v1) {
            val status = client.request(path) { this.method = method }.status
            if (path == "/v1/auth/login") {
                assertTrue(status != HttpStatusCode.OK, "login without body must not succeed")
            } else {
                assertEquals(HttpStatusCode.Unauthorized, status, "$method $path")
            }
        }
    }

    @Test
    fun configDefaultsToLoopbackAndRequiresPassword() {
        val config = ServerConfig.fromEnv(mapOf("ZENTASK_PASSWORD" to "secret"))
        assertEquals("127.0.0.1", config.host)
        assertEquals(3100, config.port)
        assertEquals("0.0.0.0", ServerConfig.fromEnv(mapOf("ZENTASK_PASSWORD" to "s", "HOST" to "0.0.0.0")).host)
        val err = assertFailsWith<IllegalStateException> { ServerConfig.fromEnv(mapOf("ZENTASK_PASSWORD" to " ")) }
        assertEquals("ZENTASK_PASSWORD is not set", err.message)
        assertFailsWith<IllegalStateException> { ServerConfig.fromEnv(emptyMap()) }
    }
}
