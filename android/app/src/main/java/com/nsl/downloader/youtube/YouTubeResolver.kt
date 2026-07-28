package com.nsl.downloader.youtube

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Turns a YouTube watch/playlist URL into concrete, directly downloadable
 * stream URLs via NewPipeExtractor.
 *
 * Everything here is blocking network work — call it from [kotlinx.coroutines.Dispatchers.IO].
 */
object YouTubeResolver {

    /** Highest-quality YouTube streams above 360p are audio-less DASH renditions. */
    data class VideoOption(
        val label: String,
        val height: Int,
        val videoUrl: String,
        /** Non-null when [videoUrl] carries no audio and must be muxed. */
        val audioUrl: String?,
        val videoSuffix: String,
        val audioSuffix: String?
    ) {
        val needsMux get() = audioUrl != null
    }

    data class AudioOption(
        val label: String,
        val bitrateKbps: Int,
        val url: String,
        val suffix: String
    )

    data class Resolved(
        val title: String,
        val url: String,
        val durationSeconds: Long,
        val videoOptions: List<VideoOption>,
        val audioOptions: List<AudioOption>
    )

    data class PlaylistItem(
        val title: String,
        val url: String,
        val durationSeconds: Long = 0,
        val uploader: String = "",
        val thumbnailUrl: String? = null
    )

    data class ResolvedPlaylist(val title: String, val items: List<PlaylistItem>)

    enum class Kind { STREAM, PLAYLIST, OTHER }

    @Volatile private var initialised = false

    fun ensureInitialised(userAgent: String) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            NewPipe.init(NewPipeDownloaderImpl(userAgent))
            initialised = true
        }
    }

    private val youTube: StreamingService get() = ServiceList.YouTube

    fun isYouTubeUrl(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")?.removePrefix("m.")?.lowercase() ?: return false
        return host == "youtube.com" || host == "youtu.be" ||
            host == "music.youtube.com" || host.endsWith(".youtube.com")
    }

    /**
     * Classifies a URL without hitting the network. A watch URL that also
     * carries `list=` counts as a STREAM — the caller offers the playlist as an
     * extra choice rather than forcing it.
     */
    fun classify(url: String): Kind {
        if (!isYouTubeUrl(url)) return Kind.OTHER
        // NewPipe can classify mobile watch URLs carrying `list=` as playlists.
        // The WebView uses exactly that form for mixes and autoplay queues, so
        // recognise concrete video routes first and keep the current video
        // available as a single-download choice.
        if (isVideoPageUrl(url)) return Kind.STREAM
        return runCatching {
            when (youTube.getLinkTypeByUrl(url)) {
                StreamingService.LinkType.STREAM -> Kind.STREAM
                StreamingService.LinkType.PLAYLIST -> Kind.PLAYLIST
                else -> Kind.OTHER
            }
        }.getOrDefault(Kind.OTHER)
    }

    private fun isVideoPageUrl(url: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(url)
        val host = uri.host.orEmpty()
            .removePrefix("www.")
            .removePrefix("m.")
            .lowercase()
        if (host == "youtu.be") {
            return@runCatching uri.pathSegments.firstOrNull().orEmpty().isNotBlank()
        }
        val route = uri.pathSegments.firstOrNull().orEmpty().lowercase()
        when (route) {
            "watch" -> !uri.getQueryParameter("v").isNullOrBlank()
            "shorts", "live", "embed" ->
                uri.pathSegments.getOrNull(1).orEmpty().isNotBlank()
            else -> false
        }
    }.getOrDefault(false)

    /** True when a watch URL is part of a playlist we could also grab whole. */
    fun playlistIdOf(url: String): String? = runCatching {
        android.net.Uri.parse(url).getQueryParameter("list")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun playlistUrlFor(playlistId: String) =
        "https://www.youtube.com/playlist?list=$playlistId"

    fun resolveVideo(url: String): Resolved {
        val info = StreamInfo.getInfo(youTube, url)
        val audio = pickAudioStreams(info.audioStreams)
        val bestMuxAudio = audio.firstOrNull { it.format == MediaFormat.M4A } ?: audio.firstOrNull()

        val options = LinkedHashMap<Int, VideoOption>()

        // Video-only DASH renditions cover everything above 360p. Restrict to
        // AVC-in-MP4 because that is what MediaMuxer can remux into an MP4
        // without re-encoding; VP9/AV1 would need a transcode we cannot do.
        if (bestMuxAudio != null) {
            info.videoOnlyStreams
                .filter { it.isProgressive() && it.format == MediaFormat.MPEG_4 }
                .filter { it.codec.orEmpty().startsWith("avc") }
                .forEach { stream ->
                    val height = stream.heightOrParsed()
                    if (height <= 0) return@forEach
                    options.putIfAbsent(
                        height,
                        VideoOption(
                            label = labelFor(height, stream.fps),
                            height = height,
                            videoUrl = stream.content,
                            audioUrl = bestMuxAudio.content,
                            videoSuffix = "mp4",
                            audioSuffix = bestMuxAudio.format?.suffix ?: "m4a"
                        )
                    )
                }
        }

        // Progressive (already muxed) streams — normally just 360p — are the
        // safest option, so they win any height collision with a DASH rendition.
        info.videoStreams
            .filter { it.isProgressive() && it.format == MediaFormat.MPEG_4 }
            .forEach { stream ->
                val height = stream.heightOrParsed()
                if (height <= 0) return@forEach
                options[height] = VideoOption(
                    label = labelFor(height, stream.fps),
                    height = height,
                    videoUrl = stream.content,
                    audioUrl = null,
                    videoSuffix = "mp4",
                    audioSuffix = null
                )
            }

        return Resolved(
            title = info.name.orEmpty().ifBlank { "YouTube video" },
            url = url,
            durationSeconds = info.duration,
            videoOptions = options.values.sortedByDescending { it.height },
            audioOptions = audio.map {
                AudioOption(
                    label = audioLabel(it),
                    bitrateKbps = it.averageBitrate.takeIf { b -> b > 0 } ?: 0,
                    url = it.content,
                    suffix = it.format?.suffix ?: "m4a"
                )
            }
        )
    }

    /**
     * Reads a whole playlist, following continuations up to [maxItems] entries
     * so long playlists do not stall the picker forever.
     *
     * [onProgress] is called with the running count after every page, both so
     * the caller can show it and so it can abort a long read — throwing from
     * the callback (e.g. `ensureActive()`) is the only way out between pages.
     */
    fun resolvePlaylist(
        url: String,
        maxItems: Int = 200,
        onProgress: (Int) -> Unit = {}
    ): ResolvedPlaylist {
        val info = PlaylistInfo.getInfo(youTube, url)
        val items = mutableListOf<PlaylistItem>()

        fun collect(source: List<org.schabi.newpipe.extractor.stream.StreamInfoItem>) {
            source.forEach { item ->
                if (items.size >= maxItems) return
                val itemUrl = item.url ?: return@forEach
                items += PlaylistItem(
                    title = item.name.orEmpty().ifBlank { "Untitled" },
                    url = itemUrl,
                    durationSeconds = item.duration.coerceAtLeast(0),
                    uploader = item.uploaderName.orEmpty(),
                    thumbnailUrl = smallestThumbnail(item.thumbnails.orEmpty())
                )
            }
        }

        collect(info.relatedItems)
        onProgress(items.size)
        var page = info.nextPage
        while (page != null && items.size < maxItems) {
            val more = PlaylistInfo.getMoreItems(youTube, url, page)
            collect(more.items)
            onProgress(items.size)
            page = more.nextPage
        }

        return ResolvedPlaylist(
            title = info.name.orEmpty().ifBlank { "YouTube playlist" },
            items = items
        )
    }

    /**
     * The picker shows thumbnails at 72dp, so it takes the smallest rendition a
     * playlist entry offers rather than pulling 200 full-size images. Height is
     * [org.schabi.newpipe.extractor.Image.HEIGHT_UNKNOWN] on some entries.
     */
    private fun smallestThumbnail(images: List<org.schabi.newpipe.extractor.Image>): String? =
        (images.filter { it.height > 0 }.minByOrNull { it.height } ?: images.firstOrNull())?.url

    /** Best audio first: prefer M4A/AAC (muxable + decodable), then bitrate. */
    private fun pickAudioStreams(streams: List<AudioStream>): List<AudioStream> =
        streams.filter { it.isProgressive() }
            .sortedWith(
                compareByDescending<AudioStream> { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            )

    private fun org.schabi.newpipe.extractor.stream.Stream.isProgressive() =
        deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl && !content.isNullOrBlank()

    /** `getHeight()` is 0 on some itags; the "1080p60" resolution string is not. */
    private fun VideoStream.heightOrParsed(): Int {
        if (height > 0) return height
        return resolution.orEmpty().substringBefore('p').toIntOrNull() ?: 0
    }

    private fun labelFor(height: Int, fps: Int): String =
        if (fps > 30) "${height}p$fps" else "${height}p"

    private fun audioLabel(stream: AudioStream): String {
        val codec = stream.format?.name ?: "audio"
        val kbps = stream.averageBitrate
        return if (kbps > 0) "$codec • $kbps kbps" else codec
    }
}
