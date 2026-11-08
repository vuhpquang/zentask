package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.ZenTaskInfo
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @Test
    fun healthReturnsOkAndVersion() = testApplication {
        application { module() }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"status":"ok","version":"${ZenTaskInfo.version}"}""", res.bodyAsText())
    }

    @Test
    fun unknownRouteReturns404() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
        val wrongMethod = client.post("/health").status
        assertTrue(wrongMethod == HttpStatusCode.NotFound || wrongMethod == HttpStatusCode.MethodNotAllowed, "got $wrongMethod")
    }
}
