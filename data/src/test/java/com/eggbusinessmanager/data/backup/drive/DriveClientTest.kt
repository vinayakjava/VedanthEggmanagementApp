package com.eggbusinessmanager.data.backup.drive

import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** The Drive REST client against a mock Drive (contracts/backup-format.md § Google Drive storage). */
class DriveClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var client: DriveClient
    private val sleeps = mutableListOf<Long>()

    @Before
    fun start() {
        server.start()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client = DriveClient(http, server.url("/"), chunkSize = 4, sleep = { sleeps += it })
    }

    @After
    fun stop() = server.shutdown()

    private fun failure(block: () -> Unit) = assertThrows(BackupException::class.java) { block() }.failure

    private fun file(content: String) = temp.newFile().apply { writeText(content) }

    @Test
    fun `resumable upload sends metadata into the app folder, then the bytes in chunks`() {
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/1")))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-3"))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-7"))
        server.enqueue(MockResponse().setBody("""{"id":"file-42","name":"x.ebmbackup"}"""))

        val id = client.upload("tok", file("0123456789"), "EggBusiness_Shop_20261108-0905.ebmbackup", mapOf("ebmFormat" to "1"))
        assertThat(id).isEqualTo("file-42")

        val start = server.takeRequest()
        assertThat(start.method).isEqualTo("POST")
        assertThat(start.path).isEqualTo("/upload/drive/v3/files?uploadType=resumable")
        assertThat(start.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(start.getHeader("X-Upload-Content-Length")).isEqualTo("10")
        val metadata = start.body.readUtf8()
        assertThat(metadata).contains("\"parents\":[\"appDataFolder\"]")
        assertThat(metadata).contains("\"ebmFormat\":\"1\"")
        assertThat(server.takeRequest().getHeader("Content-Range")).isEqualTo("bytes 0-3/10")
        assertThat(server.takeRequest().getHeader("Content-Range")).isEqualTo("bytes 4-7/10")
        val last = server.takeRequest()
        assertThat(last.getHeader("Content-Range")).isEqualTo("bytes 8-9/10")
        assertThat(last.body.readUtf8()).isEqualTo("89")
    }

    @Test
    fun `an upload interrupted mid-chunk resumes from what Drive confirmed`() {
        server.enqueue(MockResponse().setHeader("Location", server.url("/session/2")))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-3"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) // second chunk: the connection hangs and times out
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-5")) // status: 6 bytes arrived
        server.enqueue(MockResponse().setBody("""{"id":"file-7"}"""))

        assertThat(client.upload("tok", file("abcdefghij"), "b.ebmbackup", emptyMap())).isEqualTo("file-7")
        server.takeRequest() // start
        server.takeRequest() // 0-3
        server.takeRequest() // lost 4-7
        assertThat(server.takeRequest().getHeader("Content-Range")).isEqualTo("bytes */10")
        assertThat(server.takeRequest().getHeader("Content-Range")).isEqualTo("bytes 6-9/10")
    }

    @Test
    fun `list asks only for this app's backups in the hidden folder and reads every page`() {
        server.enqueue(
            MockResponse().setBody(
                """{"files":[{"id":"a","name":"A.ebmbackup","size":"2048","createdTime":"2026-11-08T09:05:00Z",
                   "appProperties":{"ebmFormat":"1","trigger":"MANUAL"}}],"nextPageToken":"p2"}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"b","name":"B.ebmbackup"}]}"""))

        val files = client.list("tok")
        assertThat(files.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(files.first().appProperties["trigger"]).isEqualTo("MANUAL")
        val first = server.takeRequest().requestUrl!!
        assertThat(first.queryParameter("spaces")).isEqualTo("appDataFolder")
        assertThat(first.queryParameter("q")).contains("key='ebmFormat' and value='1'")
        assertThat(first.queryParameter("orderBy")).isEqualTo("createdTime desc")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("pageToken")).isEqualTo("p2")
    }

    @Test
    fun `download streams the file content`() {
        server.enqueue(MockResponse().setBody("ENCRYPTED-BYTES"))
        val target = temp.newFile()
        client.download("tok", "file-42", target)
        assertThat(target.readText()).isEqualTo("ENCRYPTED-BYTES")
        assertThat(server.takeRequest().path).isEqualTo("/drive/v3/files/file-42?alt=media")
    }

    @Test
    fun `errors map to clear failures`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(failure { client.list("expired") }).isEqualTo(BackupFailure.AUTH_REQUIRED)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}"""))
        assertThat(failure { client.list("tok") }).isEqualTo(BackupFailure.DRIVE_FULL)

        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(failure { client.list("tok") }).isEqualTo(BackupFailure.UNKNOWN)

        server.shutdown()
        assertThat(failure { client.list("tok") }).isEqualTo(BackupFailure.NO_NETWORK)
    }

    @Test
    fun `rate limits and server errors are retried with backoff`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""))
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        assertThat(client.list("tok")).isEmpty()
        assertThat(sleeps).containsExactly(500L, 1000L, 2000L).inOrder()

        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        assertThat(failure { client.list("tok") }).isEqualTo(BackupFailure.UNKNOWN)
    }
}
