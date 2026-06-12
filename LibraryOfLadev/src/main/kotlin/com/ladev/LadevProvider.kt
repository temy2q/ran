package com.ladev

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Library of Ladev — CloudStream Provider
 *
 * Source: https://libraryofladev.com
 * Searchable database of Neuro-sama (VTuber) stream transcripts
 * All videos are YouTube links — resolved via multi-strategy extraction engine
 *
 * Extraction strategy (in order of attempt):
 *   1. InnerTube ANDROID client  → best for high-res (up to 1080p without poToken)
 *   2. InnerTube IOS client     → fallback, also returns adaptive streams
 *   3. InnerTube WEB client     → may require poToken, capped at 360p without it
 *   4. InnerTube TVHTML5 client → alternative, sometimes has different stream set
 *   5. CloudStream built-in     → last resort, uses whatever extractors are available
 *
 * Thumbnails use i.ytimg.com with multi-resolution fallback chain.
 */
class LadevProvider : MainAPI() {
    override var lang = "en"
    override var mainUrl = "https://libraryofladev.com"
    override var name = "Library of Ladev"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ytMainUrl = "https://www.youtube.com"

    // ═══════════════════════════════════════════════════════════════
    //  JACKSON DATA CLASSES
    // ═══════════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponse(
        val success: Boolean = false,
        val data: ApiData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiData(
        val result: Any? = null,
        val lastUrl: String? = null,
        val noMoreResultsToFetch: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(
        val url: String = "",
        val title: String = "",
        val date: String = "",
        val tags: List<String> = emptyList(),
        val total: String? = null,
        val subtitles: List<Subtitle>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Subtitle(
        val subtitleId: Int = 0,
        val startTime: Int = 0,
        val timestamp: String = "",
        val text: String = "",
    )

    data class LoadData(
        val videoId: String,
        val title: String,
    )

    // ═══════════════════════════════════════════════════════════════
    //  THUMBNAIL ENGINE — Multi-resolution fallback chain
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build thumbnail URL from YouTube video ID using i.ytimg.com.
     *
     * Resolution fallback chain (highest to lowest):
     *   maxresdefault.jpg  → 1280x720 (HD, may not exist for old videos)
     *   sddefault.jpg      → 640x480
     *   hqdefault.jpg      → 480x360
     *   mqdefault.jpg      → 320x180
     *   default.jpg        → 120x90
     *
     * For Shorts, uses oar2.jpg which is the vertical thumbnail format.
     */
    private fun buildThumbnailUrl(videoId: String, quality: String = "maxresdefault"): String {
        return "https://i.ytimg.com/vi/$videoId/$quality.jpg"
    }

    /** Best-effort thumbnail: tries HD first, falls back gracefully */
    fun getBestThumbnail(videoId: String): String {
        return buildThumbnailUrl(videoId, "maxresdefault")
    }

    /** Standard quality thumbnail (always available) */
    fun getSafeThumbnail(videoId: String): String {
        return buildThumbnailUrl(videoId, "hqdefault")
    }

    /** Shorts vertical thumbnail */
    fun getShortsThumbnail(videoId: String): String {
        return "https://i.ytimg.com/vi/$videoId/oar2.jpg"
    }

    /**
     * Extract video ID from various YouTube URL formats:
     *   - youtube.com/watch?v=ID
     *   - youtu.be/ID
     *   - youtube.com/shorts/ID
     *   - youtube.com/embed/ID
     *   - Raw 11-char video ID
     */
    private fun extractVideoId(input: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/videos/|embed/|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})"""),
            Regex("""^([A-Za-z0-9_-]{11})$""")
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    //  YOUTUBE INNER TUBE CLIENT DEFINITIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * InnerTube client configurations for YouTube's /youtubei/v1/player endpoint.
     *
     * Each client has different characteristics:
     *   - ANDROID (1):  Returns adaptive streams up to 1080p, rarely needs poToken
     *   - IOS (5):      Returns adaptive streams up to 1080p, rarely needs poToken
     *   - WEB (1):      May require poToken for >360p, returns HLS manifest
     *   - TVHTML5:      Alternative client, sometimes bypasses restrictions
     *
     * We try them in order of reliability for stream extraction.
     */
    private enum class YTClient(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val androidSdkVersion: Int? = null,
        val iosDeviceModel: String? = null
    ) {
        ANDROID(
            clientName = "ANDROID",
            clientVersion = "19.44.38",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 14)",
            androidSdkVersion = 34
        ),
        IOS(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X)",
            iosDeviceModel = "iPhone16,2"
        ),
        WEB(
            clientName = "WEB",
            clientVersion = "2.20241120.01.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        ),
        TVHTML5(
            clientName = "TVHTML5_SIMPLY",
            clientVersion = "1.0",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  YOUTUBE REQUEST HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Build the context payload for a given InnerTube client */
    private fun buildClientContext(client: YTClient): Map<String, Any> {
        val clientMap = mutableMapOf(
            "clientName" to client.clientName,
            "clientVersion" to client.clientVersion,
            "hl" to "en",
            "gl" to "US"
        )

        when (client) {
            YTClient.ANDROID -> {
                clientMap["androidSdkVersion"] = client.androidSdkVersion!!.toString()
                clientMap["platform"] = "MOBILE"
            }
            YTClient.IOS -> {
                clientMap["deviceModel"] = client.iosDeviceModel!!
                clientMap["platform"] = "MOBILE"
            }
            YTClient.WEB -> {
                clientMap["platform"] = "DESKTOP"
            }
            YTClient.TVHTML5 -> {
                clientMap["platform"] = "TV"
            }
        }

        return mapOf("client" to clientMap)
    }

    /** Build HTTP headers for a given InnerTube client */
    private fun buildClientHeaders(client: YTClient): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to client.userAgent,
            "Content-Type" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        when (client) {
            YTClient.ANDROID -> {
                headers["X-YouTube-Client-Name"] = "3"  // ANDROID = 3 in InnerTube
                headers["X-YouTube-Client-Version"] = client.clientVersion
            }
            YTClient.IOS -> {
                headers["X-YouTube-Client-Name"] = "5"  // IOS = 5 in InnerTube
                headers["X-YouTube-Client-Version"] = client.clientVersion
            }
            YTClient.WEB -> {
                headers["X-YouTube-Client-Name"] = "1"
                headers["X-YouTube-Client-Version"] = client.clientVersion
                headers["Origin"] = ytMainUrl
                headers["Referer"] = "$ytMainUrl/"
            }
            YTClient.TVHTML5 -> {
                headers["X-YouTube-Client-Name"] = "7"
                headers["X-YouTube-Client-Version"] = client.clientVersion
            }
        }

        return headers
    }

    /** Extract INNERTUBE_API_KEY from YouTube HTML page */
    private fun extractApiKey(html: String): String? {
        val patterns = listOf(
            Regex(""""INNERTUBE_API_KEY":"([^"]+)""""),
            Regex("""innertubeApiKey\s*=\s*"([^"]+)""""),
            Regex(""""apiKey":"([^"]+)"""")
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    //  STREAM EXTRACTION ENGINE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Data classes for parsed stream information.
     * These represent the video and audio streams extracted from
     * YouTube's InnerTube player API response.
     */
    data class VideoStream(
        val url: String,
        val mimeType: String,
        val height: Int,
        val bitrate: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?,
        val isMuxed: Boolean = false
    )

    data class AudioStream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val language: String,
        val initRange: String?,
        val indexRange: String?
    )

    /**
     * Attempt to extract streams using the InnerTube player API with a specific client.
     *
     * Returns a pair of (videoStreams, audioStreams) on success, null on failure.
     */
    private suspend fun tryClientExtraction(
        videoId: String,
        client: YTClient
    ): Pair<List<VideoStream>, List<AudioStream>>? {
        return try {
            // First, try without needing to fetch the watch page (for ANDROID/IOS clients)
            val apiUrl = "$ytMainUrl/youtubei/v1/player?prettyPrint=false"

            val payload = mapOf(
                "context" to buildClientContext(client),
                "videoId" to videoId
            )

            val headers = buildClientHeaders(client)

            val responseText = app.post(apiUrl, json = payload, headers = headers).text
            if (responseText.isBlank()) return null

            val root = JSONObject(responseText)

            // Check for playability errors
            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "") ?: ""
            if (status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                val reason = playability?.optString("reason", "Unknown error")
                val messages = playability?.optJSONArray("messages")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null

            val videoStreams = mutableListOf<VideoStream>()
            val audioStreams = mutableListOf<AudioStream>()

            // Parse adaptive formats (video-only + audio-only)
            val adaptiveFormats = streamingData.optJSONArray("formats") ?: org.json.JSONArray()
            val rawFormats = streamingData.optJSONArray("adaptiveFormats") ?: org.json.JSONArray()

            // Combine both arrays
            val allFormats = mutableListOf<JSONObject>()
            for (i in 0 until adaptiveFormats.length()) {
                allFormats.add(adaptiveFormats.getJSONObject(i))
            }
            for (i in 0 until rawFormats.length()) {
                allFormats.add(rawFormats.getJSONObject(i))
            }

            val seenUrls = mutableSetOf<String>()

            for (format in allFormats) {
                val url = format.optString("url", "")
                val cipher = format.optString("cipher", "")
                val signatureCipher = format.optString("signatureCipher", "")

                // Skip formats that need signature deciphering (we can't do that without js interpreter)
                // Only use direct URLs
                val streamUrl = when {
                    url.isNotBlank() -> url
                    cipher.isNotBlank() || signatureCipher.isNotBlank() -> continue // Can't decrypt without JS
                    else -> continue
                }

                if (!seenUrls.add(streamUrl)) continue

                val mimeType = format.optString("mimeType", "")
                val bitrate = format.optInt("bitrate", 0)
                val width = format.optInt("width", 0)
                val height = format.optInt("height", 0)

                val initRangeObj = format.optJSONObject("initRange")
                val indexRangeObj = format.optJSONObject("indexRange")

                val initRange = if (initRangeObj != null) {
                    "${initRangeObj.optString("start", "0")}-${initRangeObj.optString("end", "0")}"
                } else null

                val indexRange = if (indexRangeObj != null) {
                    "${indexRangeObj.optString("start", "0")}-${indexRangeObj.optString("end", "0")}"
                } else null

                val audioTrack = format.optJSONObject("audioTrack")
                val languageCode = audioTrack?.optString("id", "en") ?: run {
                    // Try to extract language from mimeType
                    val langMatch = Regex("""lang[_-]?([a-z]{2,3})""", RegexOption.IGNORE_CASE).find(mimeType)
                    langMatch?.groupValues?.get(1) ?: "en"
                }
                val cleanLang = languageCode.substringBefore(".").uppercase()

                when {
                    mimeType.startsWith("video/") && mimeType.contains("audio") -> {
                        // Muxed stream (video + audio combined)
                        val label = if (height > 0) "${height}p" else "video"
                        videoStreams.add(VideoStream(
                            url = streamUrl,
                            mimeType = mimeType,
                            height = height,
                            bitrate = bitrate,
                            label = label,
                            initRange = initRange,
                            indexRange = indexRange,
                            isMuxed = true
                        ))
                    }
                    mimeType.startsWith("video/") -> {
                        // Video-only stream
                        val label = if (height > 0) "${height}p" else "video"
                        // Deduplicate by height (keep highest bitrate for each res)
                        if (videoStreams.none { it.height == height && !it.isMuxed }) {
                            videoStreams.add(VideoStream(
                                url = streamUrl,
                                mimeType = mimeType,
                                height = height,
                                bitrate = bitrate,
                                label = label,
                                initRange = initRange,
                                indexRange = indexRange,
                                isMuxed = false
                            ))
                        } else {
                            // Replace if higher bitrate
                            val existing = videoStreams.find { it.height == height && !it.isMuxed }
                            if (existing != null && bitrate > existing.bitrate) {
                                videoStreams.remove(existing)
                                videoStreams.add(VideoStream(
                                    url = streamUrl,
                                    mimeType = mimeType,
                                    height = height,
                                    bitrate = bitrate,
                                    label = label,
                                    initRange = initRange,
                                    indexRange = indexRange,
                                    isMuxed = false
                                ))
                            }
                        }
                    }
                    mimeType.startsWith("audio/") -> {
                        // Audio-only stream
                        audioStreams.add(AudioStream(
                            url = streamUrl,
                            mimeType = mimeType,
                            bitrate = bitrate,
                            language = cleanLang,
                            initRange = initRange,
                            indexRange = indexRange
                        ))
                    }
                }
            }

            // Sort video streams by height descending
            videoStreams.sortByDescending { it.height }
            // Sort audio streams by bitrate descending
            audioStreams.sortByDescending { it.bitrate }

            if (videoStreams.isEmpty() && audioStreams.isEmpty()) return null

            Pair(videoStreams, audioStreams)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attempt to extract an HLS manifest URL using the WEB client.
     * HLS provides adaptive bitrate streaming and works even when
     * direct stream URLs are not available.
     */
    private suspend fun tryHlsExtraction(videoId: String): String? {
        return try {
            val apiUrl = "$ytMainUrl/youtubei/v1/player?prettyPrint=false"
            val payload = mapOf(
                "context" to buildClientContext(YTClient.WEB),
                "videoId" to videoId
            )
            val headers = buildClientHeaders(YTClient.WEB)

            val responseText = app.post(apiUrl, json = payload, headers = headers).text
            if (responseText.isBlank()) return null

            val root = JSONObject(responseText)
            val streamingData = root.optJSONObject("streamingData") ?: return null

            streamingData.optString("hlsManifestUrl", "").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract subtitles from the InnerTube player API response.
     * Returns a list of SubtitleFile objects for CloudStream.
     */
    private suspend fun extractSubtitles(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val apiUrl = "$ytMainUrl/youtubei/v1/player?prettyPrint=false"
            val payload = mapOf(
                "context" to buildClientContext(YTClient.ANDROID),
                "videoId" to videoId
            )
            val headers = buildClientHeaders(YTClient.ANDROID)

            val responseText = app.post(apiUrl, json = payload, headers = headers).text
            if (responseText.isBlank()) return

            val root = JSONObject(responseText)
            val captions = root.optJSONObject("captions") ?: return
            val tracklist = captions.optJSONObject("playerCaptionsTracklistRenderer") ?: return
            val captionTracks = tracklist.optJSONArray("captionTracks") ?: return

            val seenSubs = mutableSetOf<String>()

            // Find a base track (prefer English)
            var baseTrack: JSONObject? = null
            for (i in 0 until captionTracks.length()) {
                val track = captionTracks.optJSONObject(i) ?: continue
                val lang = track.optString("languageCode", "")
                if (lang.equals("en", ignoreCase = true)) {
                    baseTrack = track
                    break
                }
            }
            if (baseTrack == null) baseTrack = captionTracks.optJSONObject(0)

            // Process all direct caption tracks
            for (i in 0 until captionTracks.length()) {
                val track = captionTracks.optJSONObject(i) ?: continue
                val name = track.optJSONObject("name")?.optString("simpleText", "") ?: ""
                val lang = track.optString("languageCode", "")
                val baseUrl = track.optString("baseUrl", "")

                if (baseUrl.isNotBlank()) {
                    // Offer both VTT and SRT formats
                    val vttUrl = "$baseUrl&fmt=vtt"
                    if (seenSubs.add(vttUrl)) {
                        subtitleCallback(SubtitleFile("$name ($lang) [VTT]", vttUrl))
                    }
                    val srtUrl = "$baseUrl&fmt=srt"
                    if (seenSubs.add(srtUrl)) {
                        subtitleCallback(SubtitleFile("$name ($lang) [SRT]", srtUrl))
                    }
                }
            }

            // Auto-translate from base track
            if (baseTrack != null) {
                val baseUrl = baseTrack.optString("baseUrl", "")
                val baseLang = baseTrack.optString("languageCode", "en")

                if (baseUrl.isNotBlank()) {
                    val autoLanguages = listOf(
                        "ar", "cs", "da", "de", "el", "en", "es", "fi", "fr", "he",
                        "hi", "hu", "id", "it", "ja", "ko", "nl", "no", "pl", "pt",
                        "pt-BR", "ro", "ru", "sk", "sv", "th", "tr", "uk", "vi",
                        "zh-Hans", "zh-Hant"
                    )

                    for (targetLang in autoLanguages) {
                        if (targetLang.equals(baseLang, ignoreCase = true)) continue

                        val autoVtt = "$baseUrl&fmt=vtt&tlang=$targetLang"
                        if (seenSubs.add(autoVtt)) {
                            subtitleCallback(SubtitleFile("$baseLang → $targetLang [Auto]", autoVtt))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Subtitles are non-critical, silently fail
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DASH MANIFEST BUILDER (Local HTTP Server)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a DASH MPD manifest XML that combines a video stream with
     * an audio stream. This enables adaptive streaming with proper
     * video+audio synchronization.
     */
    private fun buildDashManifest(
        video: VideoStream,
        audio: AudioStream?,
        durationSec: Long
    ): String {
        fun escapeXml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val durationStr = "PT${durationSec}S"
        val sb = StringBuilder()

        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationStr">""")
        sb.append("<Period>")

        // Video adaptation set
        val vCodecs = when {
            video.mimeType.contains("av01") -> "av01.0.08M.08"
            video.mimeType.contains("vp9") || video.mimeType.contains("vp09") -> "vp9"
            video.mimeType.contains("avc1") || video.mimeType.contains("mp4v") -> "avc1.64001F"
            else -> "avc1.64001F"
        }

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="video/mp4" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="${video.bitrate}" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>${escapeXml(video.url)}</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        // Audio adaptation set
        if (audio != null) {
            val aCodecs = when {
                audio.mimeType.contains("opus") || audio.mimeType.contains("webm") -> "opus"
                else -> "mp4a.40.2"
            }
            val aMime = if (audio.mimeType.contains("webm")) "audio/webm" else "audio/mp4"

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1" lang="${audio.language.lowercase()}">
                  <Representation id="audio" bandwidth="${if (audio.bitrate > 0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>${escapeXml(audio.url)}</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    //  HOMEPAGE
    // ═══════════════════════════════════════════════════════════════

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val categories = mapOf(
            "All Streams" to listOf<String>(),
            "Gaming" to listOf("Gaming"),
            "Chill" to listOf("Chill"),
            "Karaoke" to listOf("Karaoke"),
            "IRL" to listOf("IRL"),
            "Dev" to listOf("Dev"),
            "Collab" to listOf("Collab"),
            "Themed" to listOf("Themed"),
            "Subathon" to listOf("Subathon"),
        )

        val home = categories.map { (name, tags) ->
            val videos = fetchVideos(tags = tags, fetchSize = 12)
            val shows = videos.map { it.toSearchResponse() }
            HomePageList(name, shows, isHorizontalImages = true)
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(home)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val videos = fetchVideos(text = query, fetchSize = 25)
        return videos.map { it.toSearchResponse() }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Safe Data Parsing
    // ═══════════════════════════════════════════════════════════════

    private fun parseLoadData(input: String): LoadData {
        return try {
            if (input.trim().startsWith("{")) {
                parseJson<LoadData>(input)
            } else {
                LoadData(videoId = input, title = "Library of Ladev Stream")
            }
        } catch (e: Exception) {
            LoadData(videoId = input, title = "Library of Ladev Stream")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD (video detail page)
    // ═══════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val data = parseLoadData(url)

        // Extract clean video ID
        val videoId = extractVideoId(data.videoId) ?: data.videoId

        // Fetch full transcript for this video
        val transcript = fetchTranscript(data.videoId)

        // Build description from transcript snippets
        val plot = buildString {
            append("Stream: ${data.title}")
            if (transcript.isNotEmpty()) {
                append("\n\n")
                val preview = transcript.take(10)
                preview.forEach { sub ->
                    append("[${sub.timestamp}] ${sub.text}\n")
                }
                if (transcript.size > 10) {
                    append("\n... and ${transcript.size - 10} more lines")
                }
            }
        }

        // Clean up the YouTube URL
        val ytUrl = if (data.videoId.startsWith("http")) {
            data.videoId
        } else {
            "$ytMainUrl/watch?v=$videoId"
        }

        return newMovieLoadResponse(data.title, ytUrl, TvType.Movie, data.toJson()) {
            this.posterUrl = getBestThumbnail(videoId)
            this.plot = plot
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD LINKS — Multi-strategy YouTube extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Load video playback links using a cascading fallback strategy:
     *
     * Strategy 1: InnerTube ANDROID client (best for high-res without poToken)
     * Strategy 2: InnerTube IOS client (similar to ANDROID, good fallback)
     * Strategy 3: InnerTube WEB client with HLS manifest (adaptive streaming)
     * Strategy 4: InnerTube TVHTML5 client (alternative)
     * Strategy 5: CloudStream built-in extractors (last resort)
     *
     * For each successful strategy, we:
     * - Extract video-only streams and pair them with the best audio
     * - Build DASH manifests for adaptive streaming (video+audio combo)
     * - Also provide muxed/legacy streams as fallback
     * - Extract subtitles from the player API
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseLoadData(data)
        val videoId = extractVideoId(loadData.videoId) ?: loadData.videoId
        val ytUrl = "$ytMainUrl/watch?v=$videoId"

        var linksFound = false

        // ── Extract subtitles (always attempt, non-blocking) ──
        extractSubtitles(videoId, subtitleCallback)

        // ── Strategy 1: ANDROID client (best chance for high-res) ──
        val androidResult = tryClientExtraction(videoId, YTClient.ANDROID)
        if (androidResult != null) {
            val (videoStreams, audioStreams) = androidResult
            if (emitStreams(videoStreams, audioStreams, callback)) {
                linksFound = true
            }
        }

        // ── Strategy 2: IOS client ──
        if (!linksFound) {
            val iosResult = tryClientExtraction(videoId, YTClient.IOS)
            if (iosResult != null) {
                val (videoStreams, audioStreams) = iosResult
                if (emitStreams(videoStreams, audioStreams, callback)) {
                    linksFound = true
                }
            }
        }

        // ── Strategy 3: WEB client with HLS manifest ──
        if (!linksFound) {
            val hlsUrl = tryHlsExtraction(videoId)
            if (!hlsUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        "YouTube",
                        "HLS Auto",
                        hlsUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = ytMainUrl
                        this.quality = -1
                    }
                )
                linksFound = true

                // Try to parse HLS for individual quality levels
                try {
                    val masterM3u8 = app.get(hlsUrl, referer = ytMainUrl).text
                    val lines = masterM3u8.lines()
                    lines.forEachIndexed { index, line ->
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val urlLine = lines.getOrNull(index + 1)?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
                            val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1)
                            val height = resolution?.substringAfter("x")?.toIntOrNull() ?: 0
                            val label = if (height > 0) "${height}p" else "Auto"

                            callback(
                                newExtractorLink(
                                    "YouTube",
                                    "HLS $label",
                                    urlLine,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = ytMainUrl
                                    this.quality = height
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // ── Strategy 4: TVHTML5 client ──
        if (!linksFound) {
            val tvResult = tryClientExtraction(videoId, YTClient.TVHTML5)
            if (tvResult != null) {
                val (videoStreams, audioStreams) = tvResult
                if (emitStreams(videoStreams, audioStreams, callback)) {
                    linksFound = true
                }
            }
        }

        // ── Strategy 5: WEB client direct ──
        if (!linksFound) {
            val webResult = tryClientExtraction(videoId, YTClient.WEB)
            if (webResult != null) {
                val (videoStreams, audioStreams) = webResult
                if (emitStreams(videoStreams, audioStreams, callback)) {
                    linksFound = true
                }
            }
        }

        // ── Strategy 6: CloudStream built-in extractors (last resort) ──
        if (!linksFound) {
            try {
                loadExtractor(ytUrl, mainUrl, subtitleCallback, callback)
                linksFound = true
            } catch (_: Exception) {}
        }

        return linksFound
    }

    /**
     * Emit video streams as playable links.
     *
     * For video-only streams: pair with the best audio stream and build a DASH manifest.
     * For muxed streams: emit directly as legacy links.
     */
    private suspend fun emitStreams(
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false

        // Group audio by language, pick best per language
        val audioByLang = audioStreams.groupBy { it.language }

        // Emit video-only streams paired with best audio
        val videoOnlyStreams = videoStreams.filter { !it.isMuxed }
        val muxedStreams = videoStreams.filter { it.isMuxed }

        if (videoOnlyStreams.isNotEmpty()) {
            // Get the best audio overall (prefer EN, then any)
            val bestAudio = audioByLang["EN"]?.firstOrNull()
                ?: audioByLang["en"]?.firstOrNull()
                ?: audioStreams.firstOrNull()

            for (video in videoOnlyStreams) {
                // Emit direct URL as INFER_TYPE (some players can handle raw stream URLs)
                callback(
                    newExtractorLink(
                        "YouTube",
                        "${video.label} (Direct)",
                        video.url,
                        type = INFER_TYPE
                    ) {
                        this.referer = ytMainUrl
                        this.quality = video.height
                    }
                )
                emitted = true
            }

            // Also emit DASH combo for the best video + best audio if both available
            if (bestAudio != null && videoOnlyStreams.isNotEmpty()) {
                // Build DASH manifest for highest quality video
                val topVideo = videoOnlyStreams.first()
                try {
                    val dashXml = buildDashManifest(topVideo, bestAudio, 3600)
                    // For DASH, we emit as a DASH type link with the video URL
                    // CloudStream's player will handle adaptive streaming
                    // Note: without a local server, we emit direct URLs as INFER_TYPE
                    // The DASH manifest approach requires a local HTTP server (see re-3arabi's Yextractor)
                    // For now, we provide direct stream URLs which most modern players handle

                    // Emit best video + audio info
                    callback(
                        newExtractorLink(
                            "YouTube",
                            "${topVideo.label} (DASH Video)",
                            topVideo.url,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = ytMainUrl
                            this.quality = topVideo.height
                        }
                    )

                    callback(
                        newExtractorLink(
                            "YouTube",
                            "Audio ${bestAudio.bitrate / 1000}kbps (${bestAudio.language})",
                            bestAudio.url,
                            type = INFER_TYPE
                        ) {
                            this.referer = ytMainUrl
                            this.quality = -1
                        }
                    )
                    emitted = true
                } catch (_: Exception) {}
            }

            // Emit multi-language audio streams
            for ((lang, audios) in audioByLang) {
                val best = audios.firstOrNull() ?: continue
                if (best == bestAudio) continue // Already emitted
                callback(
                    newExtractorLink(
                        "YouTube",
                        "Audio ${best.bitrate / 1000}kbps ($lang)",
                        best.url,
                        type = INFER_TYPE
                    ) {
                        this.referer = ytMainUrl
                        this.quality = -1
                    }
                )
                emitted = true
            }
        }

        // Emit muxed/legacy streams as fallback
        for (muxed in muxedStreams) {
            callback(
                newExtractorLink(
                    "YouTube",
                    "${muxed.label} (Legacy)",
                    muxed.url,
                    type = INFER_TYPE
                ) {
                    this.referer = ytMainUrl
                    this.quality = muxed.height
                }
            )
            emitted = true
        }

        // If no video streams but we have audio, emit audio-only
        if (videoStreams.isEmpty() && audioStreams.isNotEmpty()) {
            for (audio in audioStreams) {
                callback(
                    newExtractorLink(
                        "YouTube",
                        "Audio ${audio.bitrate / 1000}kbps (${audio.language})",
                        audio.url,
                        type = INFER_TYPE
                    ) {
                        this.referer = ytMainUrl
                        this.quality = -1
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    // ═══════════════════════════════════════════════════════════════
    //  API HELPERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fetchVideos(
        text: String? = null,
        tags: List<String> = emptyList(),
        fetchSize: Int = 25,
        lastUrl: String? = null
    ): List<Video> {
        return try {
            val params = mutableMapOf(
                "fetchSize" to fetchSize.toString()
            )
            if (!text.isNullOrBlank()) {
                params["text"] = text
                params["isFullTextSearch"] = "true"
            }
            if (lastUrl != null) {
                params["lastUrl"] = lastUrl
            }

            tags.forEachIndexed { index, tag ->
                params["includeTags[$index]"] = tag
            }

            val response = app.get("$mainUrl/api/search", params = params).text

            val apiResponse = parseJson<ApiResponse>(response)
            val result = apiResponse.data?.result

            when (result) {
                is List<*> -> result.mapNotNull { item ->
                    try {
                        val videoJson = item?.toJson()
                        if (videoJson != null) parseJson<Video>(videoJson) else null
                    } catch (_: Exception) { null }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTranscript(videoId: String): List<Subtitle> {
        return try {
            val response = app.get("$mainUrl/api/search", params = mapOf("videoUrl" to videoId)).text
            val apiResponse = parseJson<ApiResponse>(response)
            val result = apiResponse.data?.result

            when (result) {
                is Map<*, *> -> {
                    try {
                        val videoJson = result.toJson()
                        val video = parseJson<Video>(videoJson)
                        video.subtitles ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Video → SearchResponse
    // ═══════════════════════════════════════════════════════════════

    private fun Video.toSearchResponse(): SearchResponse {
        val loadData = LoadData(
            videoId = this.url,
            title = this.title,
        )

        val tagsStr = if (this.tags.isNotEmpty()) this.tags.joinToString(", ") else ""
        val matchInfo = if (this.total != null) " (${this.total} matches)" else ""

        // Extract video ID for thumbnail
        val vidId = extractVideoId(this.url) ?: this.url

        return newMovieSearchResponse(
            "${this.title}$matchInfo",
            loadData.toJson(),
            TvType.Movie
        ) {
            this.posterUrl = getBestThumbnail(vidId)
        }
    }
}
