package com.hashir.sync.api

import com.hashir.sync.api.auth.AuthApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Runs on the JVM in milliseconds -- no emulator, no device, no real Facebook calls.
 * That is the practical payoff of keeping :core-api free of Android dependencies.
 */
class AuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: AuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = GraphClient(
            config = GraphConfig(
                appId = "APP_ID",
                appSecret = "APP_SECRET",
                graphHost = server.url("/").toString().trimEnd('/'),
            ),
        )
        auth = AuthApi(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `exchange sends the app secret and returns the long lived token`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"LONG_LIVED","token_type":"bearer","expires_in":5183944}""",
            ),
        )

        val result = auth.exchangeForLongLivedToken("SHORT_LIVED")

        assertEquals("LONG_LIVED", result.accessToken)
        assertEquals(5183944L, result.expiresIn)

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/v25.0/oauth/access_token", url.encodedPath)
        assertEquals("fb_exchange_token", url.queryParameter("grant_type"))
        assertEquals("APP_ID", url.queryParameter("client_id"))
        assertEquals("APP_SECRET", url.queryParameter("client_secret"))
        assertEquals("SHORT_LIVED", url.queryParameter("fb_exchange_token"))
    }

    @Test
    fun `expired token error is recognised so the app can prompt a reconnect`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """
                {"error":{"message":"Session has expired","type":"OAuthException",
                "code":190,"error_subcode":463,"fbtrace_id":"ABC"}}
                """.trimIndent(),
            ),
        )

        try {
            auth.exchangeForLongLivedToken("STALE")
            fail("expected GraphError.Api")
        } catch (e: GraphError.Api) {
            assertEquals(190, e.code)
            assertEquals(463, e.subcode)
            assertEquals("ABC", e.fbTraceId)
            assertTrue("should be classified as an expired token", e.isTokenExpired)
            assertTrue("expired tokens are not rate limits", !e.isRateLimited)
        }
    }

    @Test
    fun `error envelope returned with HTTP 200 still fails`() = runTest {
        // Graph does this occasionally. Trusting the status code alone would let a
        // failed call look like a success and produce a corrupt stored token.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error":{"message":"Bad request","type":"OAuthException","code":100}}""",
            ),
        )

        try {
            auth.exchangeForLongLivedToken("WHATEVER")
            fail("expected GraphError.Api even though the status was 200")
        } catch (e: GraphError.Api) {
            assertEquals(100, e.code)
            assertEquals(200, e.httpStatus)
            assertNull(e.subcode)
        }
    }

    @Test
    fun `fetchMe passes the token as a bearer header`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"1001","name":"Test User","picture":{"data":{"url":"http://x/p.jpg"}}}""",
            ),
        )

        val me = auth.fetchMe("USER_TOKEN")

        assertEquals("1001", me.id)
        assertEquals("Test User", me.name)
        assertEquals("http://x/p.jpg", me.picture?.data?.url)
        assertEquals("Bearer USER_TOKEN", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `unparseable body surfaces as Unexpected rather than a crash`() = runTest {
        server.enqueue(MockResponse().setBody("<html>gateway timeout</html>"))

        try {
            auth.fetchMe("USER_TOKEN")
            fail("expected GraphError.Unexpected")
        } catch (e: GraphError.Unexpected) {
            assertTrue(e.message!!.contains("MeResponse"))
        }
    }
}
