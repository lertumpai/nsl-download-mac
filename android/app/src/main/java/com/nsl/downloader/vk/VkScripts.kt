package com.nsl.downloader.vk

/**
 * JavaScript that digs VK's real stream URLs out of a video page.
 *
 * VK never hands its media to the WebView as a plain, guessable file request:
 * the player is fed a parameter blob (progressive `urlNNN` / `cacheNNN` MP4s
 * plus `hls` / `dash_*` manifests) that arrives either inline in the document
 * or over XHR when the user opens a video. Nothing in that flow looks like a
 * downloadable resource to [com.nsl.downloader.browser.VideoSniffer], so the
 * URLs are read out of the page instead of sniffed off the wire.
 *
 * Extraction is pattern-based rather than keyed to a fixed list of field
 * names, so a renamed or newly added quality keeps working.
 */
object VkScripts {

    /**
     * Shared scanner. Collects into `window.__nslVk`, which both entry points
     * below create if it is missing — the collector has to work even when the
     * hook never ran (a tab that was already on the video before the script
     * was injected).
     */
    private val SCANNER = """
      var store = window.__nslVk;
      if (!store) {
        store = window.__nslVk = { list: [], seen: {}, title: '' };
      }

      // VK writes its parameter blob as JSON (escaped slashes) and, in places,
      // as a plain JS object literal, so quotes around the key are optional.
      function mediaRe() {
        return /["']?(url|cache)(\d{2,4})["']?\s*:\s*["']([^"']+)["']/g;
      }
      function streamRe() {
        return /["']?(hls|hls_ondemand|postlive_hls|live_hls|dash_sep|dash_ondemand|dash_webm|dash_webm_ondemand)["']?\s*:\s*["']([^"']+)["']/g;
      }
      function titleRe() {
        return /["']?md_title["']?\s*:\s*["']([^"']*)["']/;
      }

      function unescapeUrl(raw) {
        try {
          return JSON.parse('"' + raw.replace(/"/g, '\\"') + '"');
        } catch (e) {}
        return raw.replace(/\\\//g, '/').replace(/\\u0026/gi, '&');
      }

      function add(url, height, type) {
        if (!url) return;
        url = unescapeUrl(url);
        if (!/^https?:\/\//.test(url)) return;
        if (store.seen[url]) return;
        store.seen[url] = 1;
        store.list.push({ url: url, height: height, type: type });
      }

      function scanText(text) {
        if (!text || typeof text !== 'string') return;
        // Cheap reject first: these blobs are rare and the pages are huge.
        if (text.indexOf('url720') < 0 && text.indexOf('url480') < 0 &&
            text.indexOf('url360') < 0 && text.indexOf('url240') < 0 &&
            text.indexOf('url1080') < 0 && text.indexOf('cache480') < 0 &&
            text.indexOf('hls') < 0 && text.indexOf('dash_') < 0) return;

        var m, re = mediaRe();
        while ((m = re.exec(text)) !== null) {
          // `cacheNNN` is a second copy of the same rendition on another CDN.
          // It is kept as a fallback but flagged, so the primary URL wins
          // whichever order the two happen to appear in.
          add(m[3], parseInt(m[2], 10) || 0, m[1] === 'cache' ? 'mp4cache' : 'mp4');
        }
        re = streamRe();
        while ((m = re.exec(text)) !== null) {
          add(m[2], 0, m[1].indexOf('dash') === 0 ? 'dash' : 'hls');
        }
        if (!store.title) {
          var t = text.match(titleRe());
          if (t && t[1]) {
            try { store.title = JSON.parse('"' + t[1].replace(/"/g, '\\"') + '"'); }
            catch (e) { store.title = t[1]; }
          }
        }
      }

      // The id in the URL, used to notice an in-page move to another video:
      // VK is a single-page app, so nothing else says the old sources went stale.
      function videoKey() {
        var m = location.href.match(/(?:video|clip)(-?\d+_\d+)/);
        if (m) return m[1];
        var oid = location.href.match(/[?&]oid=(-?\d+)/);
        var vid = location.href.match(/[?&]id=(\d+)/);
        return oid && vid ? oid[1] + '_' + vid[1] : location.pathname;
      }

      function reset() {
        store.list = [];
        store.seen = {};
        store.title = '';
      }
    """.trimIndent()

    /**
     * Injected at document start on VK pages: watches the network responses the
     * player itself asks for.
     *
     * The blob is normally fetched, not inlined — opening a video from a feed
     * or switching quality never reloads the document — so the hook is the only
     * place those URLs are ever visible.
     */
    val HOOK = """
    (function () {
      if (window.__nslVkHook) return 'already';
      window.__nslVkHook = true;

      $SCANNER

      store.key = videoKey();
      setInterval(function () {
        var key = videoKey();
        if (key !== store.key) {
          store.key = key;
          reset();
        }
      }, 800);

      try {
        var open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function () {
          try {
            this.addEventListener('load', function () {
              try {
                var type = this.responseType;
                if (type === '' || type === 'text') scanText(this.responseText);
                else if (type === 'json') scanText(JSON.stringify(this.response));
              } catch (e) {}
            });
          } catch (e) {}
          return open.apply(this, arguments);
        };
      } catch (e) {}

      try {
        var realFetch = window.fetch;
        if (realFetch) {
          window.fetch = function () {
            return realFetch.apply(this, arguments).then(function (response) {
              // Read a clone: draining the real body would starve the player.
              try {
                response.clone().text().then(scanText, function () {});
              } catch (e) {}
              return response;
            });
          };
        }
      } catch (e) {}

      return 'installed';
    })();
    """.trimIndent()

    /**
     * Run when the user taps Download: merges whatever the hook captured with a
     * fresh sweep of the document, and returns it as JSON for [VkResolver].
     */
    val COLLECT = """
    (function () {
      $SCANNER

      var scripts = document.getElementsByTagName('script');
      for (var i = 0; i < scripts.length; i++) {
        try { scanText(scripts[i].textContent); } catch (e) {}
      }
      // The older player keeps its parameters in an attribute, not a script.
      var holders = document.querySelectorAll('[data-video], [data-params], [data-player]');
      for (var j = 0; j < holders.length; j++) {
        var el = holders[j];
        scanText(el.getAttribute('data-video'));
        scanText(el.getAttribute('data-params'));
        scanText(el.getAttribute('data-player'));
      }
      // Whatever the player actually ended up playing, as a last resort.
      var media = document.querySelectorAll('video, video source');
      for (var k = 0; k < media.length; k++) {
        var src = media[k].currentSrc || media[k].src;
        if (src && src.indexOf('blob:') !== 0) add(src, 0, 'mp4');
      }

      return {
        title: store.title || document.title || '',
        sources: store.list
      };
    })();
    """.trimIndent()
}
