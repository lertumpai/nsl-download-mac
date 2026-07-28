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
      var lastAspect = '';
      function report() {
        var playing = false;
        var media = document.querySelectorAll('video, audio');
        var active = null;
        for (var i = 0; i < media.length; i++) {
          var m = media[i];
          if (!m.paused && !m.ended && m.readyState > 2) { playing = true; active = m; break; }
        }
        // The intrinsic size decides the shape of the floating window, so it is
        // reported on every tick: a quality switch or a jump from a normal clip
        // to a vertical Short changes it without any play/pause in between.
        var video = active || document.querySelector('video');
        var w = (video && video.videoWidth) || 0;
        var h = (video && video.videoHeight) || 0;
        var aspect = w + 'x' + h;
        if (w > 0 && h > 0 && aspect !== lastAspect) {
          lastAspect = aspect;
          try { NSLBridge.onVideoAspect(w, h); } catch (e) {}
        }
        if (playing === last) return;
        last = playing;
        try { NSLBridge.onMediaState(playing, document.title || location.hostname); } catch (e) {}
      }
      ['play', 'playing', 'pause', 'ended', 'emptied', 'abort',
       'loadedmetadata', 'resize'].forEach(function (type) {
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
     * Resumes anything the window transition managed to pause.
     *
     * Going into Picture-in-Picture briefly takes the window away, and Chromium
     * pauses media the moment that happens — before [BACKGROUND_PLAYBACK]'s
     * hooks get a say, because the pause comes from the browser rather than from
     * the page. Only videos that were mid-playback are touched: `currentTime > 0`
     * and not ended, so a deliberately paused clip stays paused.
     */
    val RESUME_MEDIA = """
    (function () {
      var media = document.querySelectorAll('video, audio');
      var report = [];
      for (var i = 0; i < media.length; i++) {
        var m = media[i];
        report.push('t=' + m.currentTime.toFixed(1) + ' paused=' + m.paused +
                    ' blocked=' + (window.__nslPauseBlocked || 0));
        if (m.paused && !m.ended && m.currentTime > 0) {
          try {
            var p = m.play();
            if (p && p.catch) p.catch(function () {});
          } catch (e) {}
        }
      }
      return report.join(' | ');
    })();
    """.trimIndent()

    /**
     * Resumes only the video that was playing when PiP entry began.
     *
     * This does not use `currentTime > 0`: a newly-started clip can still be at
     * zero when Android performs the window handoff. The saved flag also avoids
     * accidentally starting a video that the user had deliberately paused.
     */
    val RESUME_PIP_MEDIA = """
    (function () {
      var video = window.__nslPipVideo;
      if (!video || !window.__nslPipWasPlaying || video.ended || !video.paused) return;
      try {
        var play = video.play();
        if (play && play.catch) play.catch(function () {});
      } catch (e) {}
    })();
    """.trimIndent()

    /**
     * Records which video is playing, without touching the layout.
     *
     * On Android 12+ the system auto-enters Picture-in-Picture with no callback
     * beforehand, so by the time [PIP_FIT_ON] runs the transition may already
     * have paused the video — too late for it to notice what was playing. This
     * runs while the app is still on screen, so [RESUME_PIP_MEDIA] always has a
     * target.
     */
    val MARK_PIP_VIDEO = """
    (function () {
      var videos = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
      var video = videos.filter(function (v) {
        return !v.paused && !v.ended;
      }).sort(function (a, b) {
        var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
        return (br.width * br.height) - (ar.width * ar.height);
      })[0];
      if (!video) return 'none';
      window.__nslPipVideo = video;
      window.__nslPipWasPlaying = true;
      return 'marked';
    })();
    """.trimIndent()

    /**
     * Lets the app refuse page-initiated pauses for a while.
     *
     * Sites pause themselves on all sorts of window events, and in a floating
     * window that reads as a bug: the user asked for the video to follow them.
     * Only `pause()` calls made *by the page* are swallowed — a real pause from
     * the browser's own media stack does not go through this API, so if the
     * video still stops the cause is not the page.
     */
    val PAUSE_GUARD = """
    (function () {
      if (window.__nslPauseGuard) return;
      window.__nslPauseGuard = true;
      window.__nslPauseBlocked = 0;
      var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
      if (!proto || !proto.pause) return;
      var realPause = proto.pause;
      proto.pause = function () {
        if (window.__nslBlockPause) {
          window.__nslPauseBlocked++;
          return;
        }
        return realPause.apply(this, arguments);
      };
    })();
    """.trimIndent()

    fun setPauseBlocked(blocked: Boolean) =
        "(function(){ window.__nslBlockPause = $blocked; })();"

    /**
     * Stretches the currently playing video's real decoder surface over the
     * viewport without moving the element out of its player.
     *
     * Mirroring a hardware-decoded video through canvas works on the emulator
     * but several phone WebView implementations return a blank frame. Moving
     * the element to `<body>` is also unsafe: YouTube and other players observe
     * that DOM removal and immediately stop playback.
     *
     * [fill] is `'aspect'` while the window is still full-screen (the video is
     * pinned to a rectangle of its own aspect ratio at the top-left, which
     * doubles as Android's source-rect hint) and `'window'` once the floating
     * window exists and the video may occupy all of it.
     */
    private fun pipFitScript(fill: String) = """
    (function () {
      var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
      if (!videos.length) return 'no-video';

      var video = videos.filter(function (v) {
        return !v.paused && !v.ended && v.readyState > 1;
      }).sort(function (a, b) {
        var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
        return (br.width * br.height) - (ar.width * ar.height);
      })[0] || videos[0];

      var old = window.__nslPipVideo;
      if (old && old !== video) {
        old.classList.remove('__nsl-pip-video');
        old.style.removeProperty('transform');
      }
      window.__nslPipVideo = video;
      window.__nslPipWasPlaying =
        window.__nslPipWasPlaying || (!video.paused && !video.ended);

      // The pinned rectangle keeps the video's own proportions: portrait clips
      // (Shorts, TikTok) get a portrait box instead of a 16:9 one with bars,
      // and the activity hands Android the very same ratio as the window shape.
      // It is anchored top-left and scaled to fit, exactly like the source-rect
      // hint computed on the Kotlin side.
      var vw = video.videoWidth || 16, vh = video.videoHeight || 9;
      var width = '100vw', height = '100vh';
      if ('$fill' !== 'window') {
        var scale = Math.min(window.innerWidth / vw, window.innerHeight / vh);
        width = Math.round(vw * scale) + 'px';
        height = Math.round(vh * scale) + 'px';
      }

      var style = document.getElementById('__nsl_pip_fit');
      if (!style) {
        style = document.createElement('style');
        style.id = '__nsl_pip_fit';
        (document.head || document.documentElement).appendChild(style);
      }
      style.textContent =
        'html.__nsl-pip-root,body.__nsl-pip-root{' +
          'margin:0!important;padding:0!important;overflow:hidden!important;' +
          'background:#000!important;width:100%!important;height:100%!important}' +
        // Anything that establishes a containing block for fixed descendants
        // (transform, filter, perspective, will-change, contain…) would trap
        // the video inside the player box instead of the window. YouTube uses
        // several of these, which is what left the floating window showing the
        // white page with the player squeezed into a corner.
        '.__nsl-pip-ancestor{' +
          'display:block!important;visibility:visible!important;opacity:1!important;' +
          'transform:none!important;filter:none!important;' +
          '-webkit-backdrop-filter:none!important;backdrop-filter:none!important;' +
          'perspective:none!important;will-change:auto!important;' +
          'contain:none!important;content-visibility:visible!important;' +
          'zoom:1!important;mask:none!important;' +
          // A stacking context on an ancestor would cap the video's z-index
          // inside it and let the backdrop paint over the picture.
          'z-index:auto!important;isolation:auto!important;' +
          'mix-blend-mode:normal!important;' +
          'overflow:visible!important;clip:auto!important;clip-path:none!important}' +
        '#__nsl_pip_backdrop{' +
          'position:fixed!important;left:0!important;top:0!important;' +
          'width:100vw!important;height:100vh!important;margin:0!important;' +
          'background:#000!important;pointer-events:none!important;' +
          'z-index:2147483646!important}' +
        'video.__nsl-pip-video{' +
          'display:block!important;visibility:visible!important;opacity:1!important;' +
          'position:fixed!important;left:0!important;top:0!important;' +
          'margin:0!important;padding:0!important;width:' + width + '!important;' +
          'height:' + height + '!important;max-width:none!important;max-height:none!important;' +
          'object-fit:contain!important;background:#000!important;' +
          'z-index:2147483647!important}';

      // An opaque backdrop under the video, parented to <html> so it can never
      // sit inside one of the page's own transformed subtrees. Without it any
      // gap around the video shows the page itself — on YouTube, a white sheet.
      var backdrop = document.getElementById('__nsl_pip_backdrop');
      if (!backdrop) {
        backdrop = document.createElement('div');
        backdrop.id = '__nsl_pip_backdrop';
        document.documentElement.appendChild(backdrop);
      }

      document.documentElement.classList.add('__nsl-pip-root');
      if (document.body) document.body.classList.add('__nsl-pip-root');
      document.querySelectorAll('.__nsl-pip-ancestor').forEach(function (node) {
        node.classList.remove('__nsl-pip-ancestor');
      });
      for (var parent = video.parentElement;
           parent && parent !== document.body && parent !== document.documentElement;
           parent = parent.parentElement) {
        parent.classList.add('__nsl-pip-ancestor');
      }
      video.classList.add('__nsl-pip-video');
      video.style.removeProperty('transform');
      try { video.setAttribute('playsinline', ''); } catch (e) {}

      // Last line of defence: if some ancestor property we did not neutralise
      // still anchors the fixed video away from the window origin, translate it
      // back by the measured offset rather than leaving it stranded.
      var rect = video.getBoundingClientRect();
      if (Math.abs(rect.left) > 1 || Math.abs(rect.top) > 1) {
        video.style.setProperty(
          'transform',
          'translate(' + (-rect.left) + 'px,' + (-rect.top) + 'px)',
          'important');
      }

      if (window.__nslPipWasPlaying && video.paused) {
        try {
          var play = video.play();
          if (play && play.catch) play.catch(function () {});
        } catch (e) {}
      }
      return 'video-ready ' + vw + 'x' + vh;
    })();
    """.trimIndent()

    /**
     * Before the activity shrinks, pin the video to a rectangle of its own
     * aspect ratio at the top of the WebView. Android receives the same
     * rectangle as its source hint, avoiding a crop of the full portrait
     * browser surface.
     */
    val PIP_FIT_PREPARE = pipFitScript("aspect")

    /** Once the PiP window exists, the video can occupy all of that window. */
    val PIP_FIT_ON = pipFitScript("window")

    /** Restores normal page layout after PiP closes or entry fails. */
    val PIP_FIT_OFF = """
    (function () {
      var video = window.__nslPipVideo;
      if (video) {
        video.classList.remove('__nsl-pip-video');
        video.style.removeProperty('transform');
      }
      document.querySelectorAll('.__nsl-pip-ancestor').forEach(function (node) {
        node.classList.remove('__nsl-pip-ancestor');
      });
      document.documentElement.classList.remove('__nsl-pip-root');
      if (document.body) document.body.classList.remove('__nsl-pip-root');
      var style = document.getElementById('__nsl_pip_fit');
      if (style) style.remove();
      var backdrop = document.getElementById('__nsl_pip_backdrop');
      if (backdrop) backdrop.remove();
      window.__nslPipVideo = null;
      window.__nslPipWasPlaying = false;
      return 'restored';
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
