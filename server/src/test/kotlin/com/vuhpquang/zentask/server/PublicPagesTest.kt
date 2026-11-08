package com.vuhpquang.zentask.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Public pages required by Google's OAuth consent screen (home, privacy, terms).
class PublicPagesTest {
    private fun page(path: String, mustContain: String) = testApplication {
        application { module() }
        val res = client.get(path)
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(ContentType.Text.Html, res.contentType()?.withoutParameters())
        assertTrue(res.bodyAsText().contains(mustContain), "$path should mention '$mustContain'")
    }

    @Test fun homePage() = page("/", "ZenTask")
    @Test fun privacyPolicyNamesTheCalendarScope() = page("/privacy", "calendar.readonly")
    @Test fun privacyPolicyHasLimitedUseStatement() = page("/privacy", "Limited Use")
    @Test fun termsPage() = page("/terms", "Terms of Service")
}
