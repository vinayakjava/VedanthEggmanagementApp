package com.eggbusinessmanager.data.backup.drive

import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A thin Google Drive REST v3 client for the app's hidden `appDataFolder` (research R11,
 * contracts/backup-format.md § Google Drive storage). Only encrypted backup files are sent; the
 * metadata holds versions, dates and sizes, never business data.
 *
 * Errors: no connection → NO_NETWORK; 401 → AUTH_REQUIRED; 403 storageQuotaExceeded → DRIVE_FULL;
 * 403 rate limit, 429 and 5xx are retried with backoff; anything else → UNKNOWN.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
    private val chunkSize: Int = DEFAULT_CHUNK,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    @Serializable
    data class DriveFile(
        val id: String,
        val name: String = "",
        val size: String? = null,
        val createdTime: String? = null,
        val appProperties: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private data class Metadata(val name: String, val parents: List<String>, val appProperties: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Resumable upload in [chunkSize] chunks; an interrupted chunk is resumed from what Drive confirmed. */
    fun upload(token: String, file: File, name: String, appProperties: Map<String, String>): String {
        val total = file.length()
        val metadata = json.encodeToString(Metadata.serializer(), Metadata(name, listOf(APP_DATA), appProperties))
        val start = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("upload/drive/v3/files").addQueryParameter("uploadType", "resumable").build())
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", OCTET)
            .header("X-Upload-Content-Length", total.toString())
            .post(metadata.toRequestBody(JSON))
            .build()
        val session = call(start) { it.header("Location") ?: throw BackupException(BackupFailure.UNKNOWN, "No upload session") }

        var offset = 0L
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                when (val result = sendChunk(raf, session, offset, total)) {
                    is ChunkResult.Done -> return result.id
                    is ChunkResult.Continue -> offset = result.nextOffset
                }
            }
        }
    }

    /** Sends one chunk; if the connection drops, asks Drive how much arrived so the caller continues from there. */
    private fun sendChunk(raf: RandomAccessFile, session: String, offset: Long, total: Long): ChunkResult {
        val length = minOf(chunkSize.toLong(), total - offset).toInt()
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        val range = if (total == 0L) "bytes */0" else "bytes $offset-${offset + length - 1}/$total"
        val put = Request.Builder().url(session).header("Content-Range", range)
            .put(bytes.toRequestBody(OCTET.toMediaType())).build()
        return try {
            call(put) { response -> chunkResult(response) }
        } catch (e: BackupException) {
            if (e.failure != BackupFailure.NO_NETWORK) throw e
            val status = Request.Builder().url(session).header("Content-Range", "bytes */$total")
                .put(ByteArray(0).toRequestBody()).build()
            call(status) { chunkResult(it) }
        }
    }

    private sealed interface ChunkResult {
        data class Done(val id: String) : ChunkResult
        data class Continue(val nextOffset: Long) : ChunkResult
    }

    private fun chunkResult(response: Response): ChunkResult = if (response.code == RESUME_INCOMPLETE) {
        // "Range: bytes=0-8388607" → continue after the last byte Drive has.
        val next = response.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
        ChunkResult.Continue(next)
    } else {
        ChunkResult.Done(parse(response, DriveFile.serializer()).id)
    }

    /** Backups in the app folder written by this app's format, newest first. */
    fun list(token: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = baseUrl.newBuilder().addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", APP_DATA)
                .addQueryParameter("q", "appProperties has { key='ebmFormat' and value='1' } and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size,createdTime,appProperties)")
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter("pageSize", "100")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val result = call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()) {
                parse(it, FileList.serializer())
            }
            files += result.files
            page = result.nextPageToken
        } while (page != null)
        return files
    }

    fun download(token: String, id: String, target: File) {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).addQueryParameter("alt", "media").build()
        call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()) { response ->
            val body = response.body ?: throw BackupException(BackupFailure.UNKNOWN, "Empty download")
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    fun delete(token: String, id: String) {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).build()
        call(Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build()) { }
    }

    /** Executes with retries for rate limits and server errors; maps failures to [BackupFailure]. */
    private fun <T> call(request: Request, onSuccess: (Response) -> T): T {
        var attempt = 0
        while (true) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw BackupException(BackupFailure.NO_NETWORK, "Drive unreachable", e)
            }
            response.use {
                if (it.isSuccessful || it.code == RESUME_INCOMPLETE) return onSuccess(it)
                val body = it.body?.string().orEmpty()
                val failure = failureFor(it.code, body)
                if (failure != null || attempt >= MAX_RETRIES) {
                    throw BackupException(failure ?: BackupFailure.UNKNOWN, "Drive error ${it.code}")
                }
                sleep(BACKOFF_MS shl attempt++)
            }
        }
    }

    /** A final failure for [code], or null when the request is worth retrying (rate limits, server errors). */
    private fun failureFor(code: Int, body: String): BackupFailure? = when {
        code == UNAUTHORIZED -> BackupFailure.AUTH_REQUIRED
        code == FORBIDDEN && "storageQuotaExceeded" in body -> BackupFailure.DRIVE_FULL
        code == TOO_MANY || code >= SERVER_ERROR || (code == FORBIDDEN && RATE_LIMIT.containsMatchIn(body)) -> null
        else -> BackupFailure.UNKNOWN
    }

    private fun <T> parse(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        json.decodeFromString(serializer, response.body?.string().orEmpty())
    } catch (e: SerializationException) {
        throw BackupException(BackupFailure.UNKNOWN, "Unexpected Drive response", e)
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupFailure.UNKNOWN, "Unexpected Drive response", e)
    }

    companion object {
        const val APP_DATA = "appDataFolder"
        const val DEFAULT_CHUNK = 8 * 1024 * 1024
        private const val OCTET = "application/octet-stream"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private const val RESUME_INCOMPLETE = 308
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
        private const val MAX_RETRIES = 3
        private const val BACKOFF_MS = 500L
        private val RATE_LIMIT = Regex("rateLimitExceeded|userRateLimitExceeded")
    }
}
