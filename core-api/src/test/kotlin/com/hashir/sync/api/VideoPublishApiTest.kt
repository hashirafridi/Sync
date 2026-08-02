package com.hashir.sync.api

import com.hashir.sync.api.publish.VideoPublishApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoPublishApiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: VideoPublishApi
    private lateinit var video: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val host = server.url("/").toString().trimEnd('/')
        val client = GraphClient(
            config = GraphConfig(
                appId = "APP",
                appSecret = "SECRET",
                graphHost = host,
                // Both hosts point at the same mock; the paths keep them distinguishable.
                uploadHost = host,
            ),
        )
        api = VideoPublishApi(client)
        video = tempFolder.newFile("clip.mp4").apply { writeBytes(ByteArray(2048) { 1 }) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueStart(videoId: String = "VID_1") {
        server.enqueue(MockResponse().setBody("""{"video_id":"$videoId","upload_url":"http://x"}"""))
    }

    private fun enqueueUploadOk() {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
    }

    private fun enqueueFinishOk() {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
    }

    private fun enqueueStatusReady() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":{"video_status":"ready","publishing_phase":{"status":"complete"}},"id":"VID_1"}""",
            ),
        )
    }

    @Test
    fun `reel publish runs start, upload and finish in order`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        enqueueFinishOk()
        enqueueStatusReady()

        val result = api.publishVideo(
            pageId = "PAGE",
            pageToken = "TOKEN",
            video = video,
            description = "hello",
        )

        assertEquals("VID_1", result.postId)

        val start = server.takeRequest()
        assertEquals("/v25.0/PAGE/video_reels", start.requestUrl!!.encodedPath)
        assertEquals("start", start.requestUrl!!.queryParameter("upload_phase"))

        val upload = server.takeRequest()
        // The version comes AFTER the endpoint name on the upload host. Getting this
        // backwards is silently accepted by every local test that only checks our own
        // formatting, and then fails against Facebook with InvalidEndpointError.
        assertEquals("/video-upload/v25.0/VID_1", upload.requestUrl!!.encodedPath)

        val finish = server.takeRequest()
        assertEquals("finish", finish.requestUrl!!.queryParameter("upload_phase"))
        assertEquals("VID_1", finish.requestUrl!!.queryParameter("video_id"))
        assertEquals("PUBLISHED", finish.requestUrl!!.queryParameter("video_state"))
        assertEquals("hello", finish.requestUrl!!.queryParameter("description"))
    }

    @Test
    fun `upload host uses OAuth scheme and declares offset and size`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        enqueueFinishOk()
        enqueueStatusReady()

        api.publishVideo("PAGE", "TOKEN", video, "x")

        server.takeRequest()
        val upload = server.takeRequest()

        // The upload host wants "OAuth <token>", not the Graph API's "Bearer <token>".
        assertEquals("OAuth TOKEN", upload.getHeader("Authorization"))
        assertEquals("0", upload.getHeader("offset"))
        assertEquals("2048", upload.getHeader("file_size"))
        assertEquals(2048L, upload.bodySize)
    }

    @Test
    fun `resuming sends only the bytes facebook does not already have`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        enqueueFinishOk()
        enqueueStatusReady()

        api.publishVideo("PAGE", "TOKEN", video, "x", resumeFromOffset = 1500)

        server.takeRequest()
        val upload = server.takeRequest()

        assertEquals("1500", upload.getHeader("offset"))
        // 2048 total minus 1500 already held. Re-sending the whole file would both waste
        // the user's data and corrupt the result.
        assertEquals(548L, upload.bodySize)
        assertEquals("2048", upload.getHeader("file_size"))
    }

    @Test
    fun `a bad resume offset is rejected before anything is uploaded`() = runTest {
        enqueueStart()
        try {
            api.publishVideo("PAGE", "TOKEN", video, "x", resumeFromOffset = 99_999)
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // start was consumed; nothing was uploaded.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `scheduling a reel sets SCHEDULED and does not wait for it to go live`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        enqueueFinishOk()

        api.publishVideo("PAGE", "TOKEN", video, "later", scheduledForEpochSeconds = 1_900_000_000L)

        server.takeRequest()
        server.takeRequest()
        val finish = server.takeRequest()

        assertEquals("SCHEDULED", finish.requestUrl!!.queryParameter("video_state"))
        assertEquals("1900000000", finish.requestUrl!!.queryParameter("scheduled_publish_time"))
        // Polling a scheduled reel would hang forever -- it never reaches published.
        assertEquals(3, server.requestCount)
    }

    // --- fallback behaviour -------------------------------------------------------

    @Test
    fun `a rejected video spec falls back to the plain videos endpoint`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        // finish rejects the media itself
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Video duration is too long","type":"OAuthException","code":100}}""",
            ),
        )
        // fallback
        server.enqueue(MockResponse().setBody("""{"id":"VIDEO_9","post_id":"PAGE_9"}"""))

        val result = api.publishVideo("PAGE", "TOKEN", video, "long clip")

        assertEquals("PAGE_9", result.postId)

        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val fallback = server.takeRequest()
        assertEquals("/v25.0/PAGE/videos", fallback.requestUrl!!.encodedPath)
        assertTrue(fallback.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `a permission failure does NOT fall back and re-upload the whole file`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"message":"(#200) Requires pages_manage_posts","type":"OAuthException","code":200}}""",
            ),
        )

        try {
            api.publishVideo("PAGE", "TOKEN", video, "x")
            fail("expected the permission error to propagate")
        } catch (e: GraphError.Api) {
            assertTrue(e.isPermissionDenied)
            // start + upload + finish only. Falling back here would push the entire file
            // a second time just to fail again on the same missing permission.
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `an expired token does NOT fall back`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Session expired","type":"OAuthException","code":190,"error_subcode":463}}""",
            ),
        )

        try {
            api.publishVideo("PAGE", "TOKEN", video, "x")
            fail("expected the token error to propagate")
        } catch (e: GraphError.Api) {
            assertTrue(e.isTokenExpired)
            assertEquals(3, server.requestCount)
        }
    }

    // --- status polling -----------------------------------------------------------

    @Test
    fun `a processing failure after upload is surfaced, not reported as success`() = runTest {
        enqueueStart()
        enqueueUploadOk()
        enqueueFinishOk()
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":{"processing_phase":{"status":"error",
                "errors":[{"code":6001,"message":"Video could not be processed"}]}}}
                """.trimIndent(),
            ),
        )

        try {
            api.publishVideo("PAGE", "TOKEN", video, "x")
            fail("expected the processing failure to surface")
        } catch (e: GraphError.Api) {
            // A 200 on upload only means the bytes arrived. Treating that as published
            // would tell the user a post exists when it never appeared.
            assertEquals(6001, e.code)
            assertTrue(e.fbMessage.contains("could not be processed"))
        }
    }

    @Test
    fun `uploadedBytes reports what facebook already holds, for resuming`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"status":{"uploading_phase":{"status":"in_progress","bytes_transferred":4096}}}""",
            ),
        )

        assertEquals(4096L, api.uploadedBytes("VID_1", "TOKEN"))
    }

    @Test
    fun `uploadedBytes is zero when nothing has arrived yet`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":{}}"""))
        assertEquals(0L, api.uploadedBytes("VID_1", "TOKEN"))
    }
}
