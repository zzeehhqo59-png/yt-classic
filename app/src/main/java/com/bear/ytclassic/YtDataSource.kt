package com.bear.ytclassic

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Small OkHttp data source that sends googlevideo requests the way YouTube's own clients do
 * (POST "x\0" + youtube.com origin for web URLs, matching user agent for visionOS URLs, rn counter).
 */
class YtDataSource(private val client: OkHttpClient) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var response: okhttp3.Response? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        var url = dataSpec.uri.toString()
        val gv = url.contains("/videoplayback")
        val web = gv && YoutubeParsingHelper.isWebStreamingUrl(url)
        val visionOs = gv && YoutubeParsingHelper.isVisionOsStreamingUrl(url)
        if (gv && !url.contains("/manifest/")) url += "&rn=" + RN.incrementAndGet()
        val pos = dataSpec.position
        val len = dataSpec.length
        val b = Request.Builder().url(url)
        if (pos != 0L || len != C.LENGTH_UNSET.toLong()) {
            b.header("Range", "bytes=$pos-" + if (len != C.LENGTH_UNSET.toLong()) (pos + len - 1).toString() else "")
        }
        when {
            web -> {
                b.header("User-Agent", DownloaderImpl.USER_AGENT)
                b.header("Origin", "https://www.youtube.com")
                b.header("Referer", "https://www.youtube.com/")
                b.post(byteArrayOf(0x78, 0).toRequestBody())
            }
            visionOs -> b.header("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null))
            else -> b.header("User-Agent", DownloaderImpl.USER_AGENT)
        }
        dataSpec.httpRequestHeaders.forEach { (k, v) -> b.header(k, v) }
        val r = client.newCall(b.build()).execute()
        if (!r.isSuccessful) {
            val code = r.code; val message = r.message; val headers = r.headers.toMultimap()
            r.close()
            throw HttpDataSource.InvalidResponseCodeException(code, message, null, headers, dataSpec, ByteArray(0))
        }
        response = r
        val stream = r.body.byteStream()
        input = stream
        val ignoredRange = r.code == 200 && pos > 0
        if (ignoredRange) {
            var skip = pos
            while (skip > 0) {
                val s = stream.skip(skip)
                if (s <= 0) { if (stream.read() < 0) throw EOFException() else skip-- } else skip -= s
            }
        }
        val cl = r.body.contentLength()
        remaining = when {
            len != C.LENGTH_UNSET.toLong() -> len
            cl >= 0 -> cl - (if (ignoredRange) pos else 0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = input?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        response?.close()
        input = null; response = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource(client)
    }

    companion object { private val RN = AtomicLong(0) }
}
