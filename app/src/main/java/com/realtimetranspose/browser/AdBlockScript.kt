package com.realtimetranspose.browser

/**
 * YouTube ad-blocking, injected at document-start (before any page script
 * runs — see [com.realtimetranspose.ui.BrowserScreen] for why that ordering
 * matters and `onPageFinished` doesn't work).
 *
 * Domain/host blocklists (`shouldIntercepto­Request`) do **not** stop
 * YouTube's actual video ads — Google deliberately serves them from the same
 * googlevideo.com infrastructure as real video, specifically to defeat that
 * approach. What works (and what uBlock Origin's own YouTube filters do) is
 * stripping the ad-describing fields out of YouTube's player-response JSON
 * *before* YouTube's own code reads it, via four hook points: `JSON.parse`,
 * `Response.prototype.json`, `fetch`, and `XMLHttpRequest` — because we can't
 * know in advance which path any given YouTube build uses to read its own
 * `/youtubei/v1/player` response, plus a `window.ytInitialPlayerResponse`
 * accessor trap for the version embedded directly in the page.
 *
 * Hard rule shared with [com.realtimetranspose.ui.BrowserScreen]'s pitch
 * hook: this script must never replace, clone, or re-parent the `<video>`
 * element (some ad-blockers do this via an iframe swap) — that would
 * permanently break `createMediaElementSource()`, which only works once per
 * element.
 *
 * Every prune fires [TransposeJsBridge.onAdBlockEvent] — that counter going
 * up is the acceptance signal ("it actually stripped something"), not
 * "I didn't happen to see an ad" (most videos have no ad inventory to begin
 * with, so silence alone proves nothing).
 */
const val AD_BLOCK_SCRIPT = """
(function() {
  if (window.__transposeAdBlock) return;
  window.__transposeAdBlock = true;

  var nativeJsonParse = JSON.parse;
  var nativeJsonStringify = JSON.stringify;

  function report(tag) {
    if (window.TransposeBridge && window.TransposeBridge.onAdBlockEvent) {
      try { window.TransposeBridge.onAdBlockEvent(tag); } catch (e) {}
    }
  }

  var PRUNE_KEYS = [
    'adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adSignalsInfo',
    'adSlotRenderer', 'promotedSparklesWebRenderer', 'promotedVideoRenderer',
    'compactPromotedVideoRenderer', 'brandVideoShelfRenderer', 'shoppingCarouselRenderer',
    'merchandiseShelfRenderer'
  ];

  function pruneObject(obj, depth) {
    if (!obj || typeof obj !== 'object' || depth > 12) return obj;
    if (Array.isArray(obj)) {
      for (var i = 0; i < obj.length; i++) {
        pruneObject(obj[i], depth + 1);
      }
      return obj;
    }
    for (var key in obj) {
      if (!Object.prototype.hasOwnProperty.call(obj, key)) continue;
      if (PRUNE_KEYS.indexOf(key) !== -1) {
        delete obj[key];
        report('prune:' + key);
        continue;
      }
      pruneObject(obj[key], depth + 1);
    }
    // Shorts: reel_watch_sequence responses carry ad entries as
    // entries[].command.reelWatchEndpoint.adClientParams.isAd === true —
    // drop those entries outright rather than trying to delete a field.
    if (Array.isArray(obj.entries)) {
      var kept = [];
      for (var e = 0; e < obj.entries.length; e++) {
        var entry = obj.entries[e];
        var isAd = entry && entry.command && entry.command.reelWatchEndpoint &&
          entry.command.reelWatchEndpoint.adClientParams &&
          entry.command.reelWatchEndpoint.adClientParams.isAd;
        if (isAd) {
          report('prune:reelWatchAd');
        } else {
          kept.push(entry);
        }
      }
      if (kept.length !== obj.entries.length) obj.entries = kept;
    }
    return obj;
  }

  function pruneJsonText(text) {
    try {
      var obj = nativeJsonParse(text);
      pruneObject(obj, 0);
      return nativeJsonStringify(obj);
    } catch (e) {
      return text;
    }
  }

  function shouldIntercept(url) {
    if (typeof url !== 'string') return false;
    return /\/youtubei\/v1\/(player|next|browse|reel_watch_sequence)/.test(url) ||
      /\/watch\?/.test(url) ||
      /\/playlist\?list=/.test(url);
  }

  // --- Hook 1: JSON.parse -----------------------------------------------
  JSON.parse = function() {
    var result = nativeJsonParse.apply(JSON, arguments);
    if (result && typeof result === 'object') pruneObject(result, 0);
    return result;
  };

  // --- Hook 2: Response.prototype.json -----------------------------------
  if (window.Response && Response.prototype.json) {
    var origResponseJson = Response.prototype.json;
    Response.prototype.json = function() {
      return origResponseJson.apply(this, arguments).then(function(obj) {
        pruneObject(obj, 0);
        return obj;
      });
    };
  }

  // --- Hook 3: fetch (text path) ------------------------------------------
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function() {
      var input = arguments[0];
      var url = (input && input.url) || input || '';
      if (!shouldIntercept(url)) return origFetch.apply(this, arguments);
      return origFetch.apply(this, arguments).then(function(response) {
        return response.clone().text().then(function(text) {
          var patched = pruneJsonText(text);
          if (patched === text) return response;
          report('fetch-pruned');
          return new Response(patched, {
            status: response.status,
            statusText: response.statusText,
            headers: response.headers
          });
        }).catch(function() { return response; });
      });
    };
  }

  // --- Hook 4: XMLHttpRequest ----------------------------------------------
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__transposeUrl = url;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    var xhr = this;
    if (shouldIntercept(xhr.__transposeUrl)) {
      xhr.addEventListener('readystatechange', function() {
        if (xhr.readyState === 4 && !xhr.__transposePatched) {
          try {
            var text = xhr.responseText;
            var patched = pruneJsonText(text);
            if (patched !== text) {
              xhr.__transposePatched = true;
              Object.defineProperty(xhr, 'responseText', { value: patched, configurable: true });
              Object.defineProperty(xhr, 'response', { value: patched, configurable: true });
              report('xhr-pruned');
            }
          } catch (e) {}
        }
      });
    }
    return origSend.apply(this, arguments);
  };

  // --- Hook 5: window.ytInitialPlayerResponse accessor trap ----------------
  // Runs at document-start, before YouTube's own inline script assigns this
  // global — defining the property first lets us intercept that assignment.
  (function() {
    var stored;
    try {
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function() { return stored; },
        set: function(v) {
          if (v && typeof v === 'object') {
            pruneObject(v, 0);
            report('ytInitialPlayerResponse-pruned');
          }
          stored = v;
        }
      });
    } catch (e) {
      report('trap-error:' + e.message);
    }
  })();
})();
"""
