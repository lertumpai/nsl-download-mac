package com.nsl.downloader.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Network-level ad/tracker blocking for the browser WebViews.
 *
 * Matching is host-suffix based plus a small set of URL patterns. Media hosts
 * (googlevideo.com, CDNs) are never matched, so blocking never starves the
 * video sniffer — in-player ads that share the media host are handled in JS by
 * [BrowserScripts.AD_BLOCK_COSMETIC].
 */
object AdBlocker {

    /** Blocked when the request host equals one of these or is a subdomain. */
    private val BLOCKED_HOSTS = setOf(
        // Google ad stack
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "googletagmanager.com", "google-analytics.com",
        "adservice.google.com", "app-measurement.com", "2mdn.net",
        // Exchanges / networks
        "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com",
        "adform.net", "bidswitch.net", "33across.com", "sharethrough.com",
        "amazon-adsystem.com", "media.net", "teads.tv", "yieldmo.com",
        "an.yandex.ru", "zedo.com", "adcolony.com", "applovin.com",
        "unityads.unity3d.com", "inmobi.com", "smaato.net", "mopub.com",
        // Content recommendation / native ads
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "adthrive.com", "mediavine.com", "zergnet.com",
        // Pop / malvertising heavy networks
        "propellerads.com", "popads.net", "popcash.net", "adsterra.com",
        "hilltopads.net", "exoclick.com", "juicyads.com", "trafficjunky.com",
        "poweredbyliquidfire.mobi", "onclickads.net",
        // Analytics / attribution
        "scorecardresearch.com", "quantserve.com", "moatads.com", "chartbeat.com",
        "hotjar.com", "mixpanel.com", "segment.io", "branch.io", "appsflyer.com",
        "adjust.com", "kochava.com", "newrelic.com", "bugsnag.com",
        "crazyegg.com", "clicktale.net", "optimizely.com"
    )

    /** Blocked anywhere in the URL (path/query), regardless of host. */
    private val BLOCKED_PATTERNS = listOf(
        "/pagead/", "/pagead2/", "/adserver", "/adservice", "/ad_server",
        "/adframe", "/ad_frame", "/adsbygoogle", "/googleads", "/doubleclick",
        "/prebid", "/advertisement", "/banner_ad", "/sponsored_ad",
        "/api/stats/ads", "/get_midroll_info", "/ptracking", "/ad_break",
        "/track/ads", "/adsense", "/popunder", "/interstitial_ad"
    )

    /** Hosts that must never be blocked (media delivery, first-party players). */
    private val ALLOW_HOSTS = setOf(
        "googlevideo.com", "ytimg.com", "ggpht.com"
    )

    fun shouldBlock(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false

        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        if (ALLOW_HOSTS.any { host.matches(it) }) return false
        if (BLOCKED_HOSTS.any { host.matches(it) }) return true
        return BLOCKED_PATTERNS.any { lower.contains(it) }
    }

    /** An empty 200 response — quieter for page scripts than a hard failure. */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Host equals [domain] or is a subdomain of it. */
    private fun String.matches(domain: String): Boolean =
        this == domain || endsWith(".$domain")
}
