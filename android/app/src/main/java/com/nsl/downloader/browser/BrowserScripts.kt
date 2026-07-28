package com.nsl.downloader.browser

/**
 * JavaScript injected into every page the browser loads.
 *
 * [MEDIA_STATE] and [BACKGROUND_PLAYBACK] run at document start (before the
 * page's own scripts bind their handlers); [AD_BLOCK_COSMETIC] runs once the
 * DOM exists.
 */
object BrowserScripts {

    /**
     * Reports whether anything is playing via the `NSLBridge` binding.
     *
     * Injected regardless of playback mode: the foreground service needs it,
     * and so does the Picture-in-Picture decision — the app only hands itself
     * to the system when a video is actually running.
     */
    val MEDIA_STATE = """
    (function () {
      if (window.__nslMedia) return;
      window.__nslMedia = true;

      var last = null;
      function report() {
        var playing = false;
        var media = document.querySelectorAll('video, audio');
        for (var i = 0; i < media.length; i++) {
          var m = media[i];
          if (!m.paused && !m.ended && m.readyState > 2) { playing = true; break; }
        }
        if (playing === last) return;
        last = playing;
        try { NSLBridge.onMediaState(playing, document.title || location.hostname); } catch (e) {}
      }
      ['play', 'playing', 'pause', 'ended', 'emptied', 'abort'].forEach(function (type) {
        document.addEventListener(type, function () { setTimeout(report, 150); }, true);
      });
      setInterval(report, 2000);
    })();
    """.trimIndent()

    /**
     * Pins the Page Visibility API to "visible" and swallows the lifecycle
     * events sites use to auto-pause (YouTube pauses on `visibilitychange`).
     *
     * Only needed for background *audio*: in Picture-in-Picture the window stays
     * genuinely visible, so the page has no reason to pause itself.
     */
    val BACKGROUND_PLAYBACK = """
    (function () {
      if (window.__nslBg) return;
      window.__nslBg = true;

      var define = function (obj, prop, value) {
        try {
          Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true });
        } catch (e) {}
      };
      define(document, 'hidden', false);
      define(document, 'webkitHidden', false);
      define(document, 'mozHidden', false);
      define(document, 'visibilityState', 'visible');
      define(document, 'webkitVisibilityState', 'visible');
      define(document, 'hasFocus', function () { return true; });

      var muted = ['visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange',
                   'pagehide', 'freeze', 'blur'];
      var addEL = EventTarget.prototype.addEventListener;
      EventTarget.prototype.addEventListener = function (type, fn, opts) {
        if (muted.indexOf(type) !== -1 && (this === document || this === window)) return;
        return addEL.call(this, type, fn, opts);
      };
      muted.forEach(function (type) {
        addEL.call(document, type, function (e) { e.stopImmediatePropagation(); }, true);
        addEL.call(window, type, function (e) { e.stopImmediatePropagation(); }, true);
      });
      setInterval(function () {
        document.onvisibilitychange = null;
        document.onwebkitvisibilitychange = null;
        window.onblur = null;
        window.onpagehide = null;
      }, 1000);
    })();
    """.trimIndent()

    /**
     * Hides ad slots that survive network blocking (they are same-origin markup)
     * and skips YouTube's in-player ads, which are served from the same host as
     * the real video and therefore cannot be blocked by URL.
     */
    val AD_BLOCK_COSMETIC = """
    (function () {
      if (window.__nslAd) return;
      var root = document.head || document.documentElement;
      if (!root) return;
      window.__nslAd = true;

      var hide = [
        'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
        '[id^="aswift_"]', 'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication"]',
        'iframe[src*="amazon-adsystem"]', 'iframe[src*="/ads/"]', 'iframe[id*="google_ads"]',
        '.ad-banner', '.ad-container', '.ad-wrapper', '.ad-slot', '.adsbox', '.ad-placeholder',
        '.advertisement', '.sponsored-ad', '#ad-banner', '#adBanner', '#ads-container',
        '.taboola', '#taboola-below-article', '.OUTBRAIN', '.trc_related_container',
        'ytd-ad-slot-renderer', 'ytd-display-ad-renderer', 'ytd-promoted-video-renderer',
        'ytd-promoted-sparkles-web-renderer', 'ytd-in-feed-ad-layout-renderer',
        'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer',
        '#player-ads', '#masthead-ad', '.ytp-ad-overlay-container', '.ytp-ad-overlay-slot',
        '.video-ads', '.ytp-featured-product'
      ].join(',');

      var style = document.createElement('style');
      style.id = '__nsl_adblock';
      style.textContent = hide + '{display:none!important;visibility:hidden!important;height:0!important;}';
      root.appendChild(style);
      setInterval(function () {
        if (!document.getElementById('__nsl_adblock')) {
          (document.head || document.documentElement).appendChild(style);
        }
      }, 3000);

      if (/(^|\.)youtube\.com${'$'}/.test(location.hostname) ||
          /(^|\.)youtube-nocookie\.com${'$'}/.test(location.hostname)) {
        setInterval(function () {
          var skip = document.querySelector(
            '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
            '.ytp-ad-skip-button-slot button');
          if (skip) skip.click();
          var close = document.querySelector('.ytp-ad-overlay-close-button');
          if (close) close.click();

          // Unskippable ads: jump to the end of the ad clip.
          if (document.querySelector('.ad-showing, .ad-interrupting')) {
            var v = document.querySelector('video');
            if (v && isFinite(v.duration) && v.duration > 0) {
              v.currentTime = v.duration;
              if (v.paused) { try { v.play(); } catch (e) {} }
            }
          }
        }, 400);
      }
    })();
    """.trimIndent()
}
