package com.hashir.sync.api

import com.hashir.sync.api.publish.PublishApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PublishApiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var publish: PublishApi

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
        publish = PublishApi(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- text ---------------------------------------------------------------------

    @Test
    fun `text post goes to the page feed with the page token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"12345_67890"}"""))

        val result = publish.publishText(
            pageId = "12345",
            pageToken = "PAGE_TOKEN",
            message = "Hello world",
        )

        assertEquals("12345_67890", result.postId)
        assertEquals("https://www.facebook.com/12345_67890", result.permalinkUrl)

        val request = server.takeRequest()
        assertEquals("/v25.0/12345/feed", request.requestUrl!!.encodedPath)
        assertEquals("Bearer PAGE_TOKEN", request.getHeader("Authorization"))
        // Decoded rather than matched raw: OkHttp percent-encodes spaces as %20, and
        // asserting on the encoded form would be testing OkHttp's choices, not ours.
        assertTrue(decodeForm(request.body.readUtf8()).contains("message=Hello world"))
    }

    @Test
    fun `link is included when supplied`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"1_2"}"""))

        publish.publishText(
            pageId = "1",
            pageToken = "T",
            message = "Look",
            link = "https://example.com/a",
        )

        val body = decodeForm(server.takeRequest().body.readUtf8())
        assertTrue(body.contains("link=https://example.com/a"))
    }

    @Test
    fun `scheduling sends both published false and the timestamp`() = runTest {
        // Sending only the timestamp makes Facebook publish immediately. Getting this
        // wrong is silent and unrecoverable, so both fields are asserted together.
        server.enqueue(MockResponse().setBody("""{"id":"1_2"}"""))

        publish.publishText(
            pageId = "1",
            pageToken = "T",
            message = "Later",
            scheduledForEpochSeconds = 1_900_000_000L,
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue("must mark the post unpublished", body.contains("published=false"))
        assertTrue(body.contains("scheduled_publish_time=1900000000"))
    }

    @Test
    fun `an immediate post sends no scheduling fields at all`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"1_2"}"""))

        publish.publishText(pageId = "1", pageToken = "T", message = "Now")

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("published="))
        assertFalse(body.contains("scheduled_publish_time"))
    }

    @Test
    fun `an empty post is rejected before hitting the network`() = runTest {
        try {
            publish.publishText(pageId = "1", pageToken = "T", message = "   ")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    // --- photo --------------------------------------------------------------------

    @Test
    fun `photo is uploaded as multipart with caption and source`() = runTest {
        val photo = tempFolder.newFile("holiday.jpg").apply { writeBytes(ByteArray(64) { 7 }) }
        server.enqueue(MockResponse().setBody("""{"id":"PHOTO_1","post_id":"PAGE_POST_1"}"""))

        val result = publish.publishPhoto(
            pageId = "999",
            pageToken = "PAGE_TOKEN",
            photo = photo,
            caption = "Nice view",
        )

        // post_id is the feed post; id is only the photo. Linking to the photo id would
        // send the user somewhere that is not their post.
        assertEquals("PAGE_POST_1", result.postId)
        assertEquals("PHOTO_1", result.rawId)

        val request = server.takeRequest()
        assertEquals("/v25.0/999/photos", request.requestUrl!!.encodedPath)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"caption\""))
        assertTrue(body.contains("Nice view"))
        assertTrue(body.contains("name=\"source\""))
        assertTrue(body.contains("holiday.jpg"))
    }

    @Test
    fun `photo falls back to the plain id when no post_id comes back`() = runTest {
        val photo = tempFolder.newFile("x.jpg").apply { writeBytes(ByteArray(8)) }
        server.enqueue(MockResponse().setBody("""{"id":"ONLY_ID"}"""))

        val result = publish.publishPhoto("1", "T", photo, "c")

        assertEquals("ONLY_ID", result.postId)
    }

    @Test
    fun `a missing file fails before any upload is attempted`() = runTest {
        try {
            publish.publishPhoto("1", "T", File("does-not-exist.jpg"), "c")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `content type is derived from the file extension`() {
        assertEquals("image/png", PublishApi.guessImageMediaType(File("a.PNG")))
        assertEquals("image/webp", PublishApi.guessImageMediaType(File("a.webp")))
        assertEquals("image/jpeg", PublishApi.guessImageMediaType(File("a.jpeg")))
        assertEquals("image/jpeg", PublishApi.guessImageMediaType(File("a.unknown")))
    }

    // --- errors -------------------------------------------------------------------

    @Test
    fun `missing permission is classified so the UI can say what to fix`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """
                {"error":{"message":"(#200) Requires pages_manage_posts permission",
                "type":"OAuthException","code":200}}
                """.trimIndent(),
            ),
        )

        try {
            publish.publishText("1", "T", "hi")
            fail("expected GraphError.Api")
        } catch (e: GraphError.Api) {
            assertTrue(e.isPermissionDenied)
            assertFalse(e.isTokenExpired)
            assertFalse(e.isRateLimited)
        }
    }

    /**
     * Guards the boundary between the two classifications. They used to overlap: code 4
     * is a rate limit, but an over-broad permission range also claimed it, so a throttled
     * publish was reported as a permissions problem and sent the user to check Page roles
     * instead of simply waiting.
     */
    @Test
    fun `rate limiting is classified separately from permission failures`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Application request limit reached","type":"OAuthException","code":4}}""",
            ),
        )

        try {
            publish.publishText("1", "T", "hi")
            fail("expected GraphError.Api")
        } catch (e: GraphError.Api) {
            assertTrue(e.isRateLimited)
            assertFalse(e.isPermissionDenied)
        }
    }

    private fun decodeForm(body: String): String =
        java.net.URLDecoder.decode(body, "UTF-8")
}
