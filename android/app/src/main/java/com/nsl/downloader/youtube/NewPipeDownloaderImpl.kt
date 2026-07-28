package com.nsl.downloader.youtube

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [Downloader] for NewPipeExtractor.
 *
 * The extractor is chatty (player JS, InnerTube JSON, playlist pages), so this
 * shares one tuned client and mirrors the browser's User-Agent — YouTube serves
 * different player payloads to obviously-scripted clients.
 */
class NewPipeDownloaderImpl(private val userAgent: String) : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = OkRequest.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", userAgent)

        request.headers().forEach { (name, values) ->
            // A null value list means "remove this header" in the extractor's contract.
            builder.removeHeader(name)
            values?.forEach { value -> builder.addHeader(name, value) }
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha challenge requested", request.url())
        }

        val responseBody = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString()
        )
    }
}
