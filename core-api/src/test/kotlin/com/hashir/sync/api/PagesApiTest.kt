package com.hashir.sync.api

import com.hashir.sync.api.pages.PagesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PagesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var pages: PagesApi

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
        pages = PagesApi(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `requests the tasks field so publish permission is known up front`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        pages.listPages("USER_TOKEN")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/v25.0/me/accounts", url.encodedPath)
        val fields = url.queryParameter("fields")!!
        assertTrue("must request access_token", fields.contains("access_token"))
        assertTrue("must request tasks", fields.contains("tasks"))
    }

    @Test
    fun `pages without CREATE_CONTENT are flagged as not publishable`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":"1","name":"Publishable","access_token":"T1",
                   "tasks":["ANALYZE","CREATE_CONTENT","MANAGE"],
                   "picture":{"data":{"url":"http://x/1.jpg"}}},
                  {"id":"2","name":"Read Only","access_token":"T2","tasks":["ANALYZE"]}
                ]}
                """.trimIndent(),
            ),
        )

        val result = pages.listPages("USER_TOKEN")

        assertEquals(2, result.size)
        assertTrue(result[0].canCreateContent)
        assertEquals("http://x/1.jpg", result[0].pictureUrl)
        assertFalse("Read Only must be disabled in the dashboard", result[1].canCreateContent)
    }

    @Test
    fun `pagination is followed to the end`() = runTest {
        val nextUrl = server.url("/v25.0/me/accounts?after=CURSOR").toString()
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[{"id":"1","name":"Page One","access_token":"T1","tasks":["CREATE_CONTENT"]}],
                 "paging":{"next":"$nextUrl"}}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"2","name":"Page Two","access_token":"T2","tasks":["CREATE_CONTENT"]}]}""",
            ),
        )

        val result = pages.listPages("USER_TOKEN")

        // A user with more than 100 Pages would otherwise silently lose the tail.
        assertEquals(listOf("Page One", "Page Two"), result.map { it.name })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `each page carries its own token so one dashboard can span several accounts`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":"1","name":"A","access_token":"TOKEN_A","tasks":["CREATE_CONTENT"]},
                  {"id":"2","name":"B","access_token":"TOKEN_B","tasks":["CREATE_CONTENT"]}
                ]}
                """.trimIndent(),
            ),
        )

        val result = pages.listPages("USER_TOKEN")

        assertEquals("TOKEN_A", result.first { it.id == "1" }.accessToken)
        assertEquals("TOKEN_B", result.first { it.id == "2" }.accessToken)
    }
}
