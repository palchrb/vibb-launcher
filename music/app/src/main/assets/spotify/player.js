// The music app's Spotify page glue (design 26c §5.3). Part of handy (palchrb/vibb-launcher),
// licensed under the GNU General Public License v3.0 or later (LICENSE). Ours; Spotify's Web Playback
// SDK is loaded after it, at run time, and never copied (§7.2).
//
// The page bridge, both ways (SpotifyPage in WebPolicy.kt holds the names; PlayerJsTest checks this
// file against them):
// - page -> host: the one interface object VibbSpotify - ready(deviceId), notReady(deviceId),
//   needToken(requestId), state(json), error(kind, message). The host validates and caps each.
// - host -> page: vibb.connect(name), vibb.token(requestId, tokenOrNull), vibb.pause(), vibb.resume(),
//   vibb.seek(ms), vibb.next(), vibb.previous(), vibb.volume(v), vibb.state(), vibb.disconnect().
//
// The token reaches the SDK only through getOAuthToken's callback: never the DOM, a URL, storage or
// a log. The page itself fetches nothing.
(function () {
  'use strict';

  var bridge = window.VibbSpotify;
  var player = null;
  var sdkReady = false;
  var wanted = null;        // the player's name, once the host asked to connect
  var tick = null;          // the 5 s state post while playing
  var pending = {};         // getOAuthToken callbacks by request id
  var nextRequest = 1;

  var TICK_MS = 5000;
  var NEIGHBOURS = 3;
  var TEXT_MAX = 200;
  var ARTISTS_MAX = 10;

  function text(v) {
    return typeof v === 'string' ? v.slice(0, TEXT_MAX) : null;
  }

  function num(v) {
    return typeof v === 'number' && isFinite(v) && v >= 0 ? Math.floor(v) : 0;
  }

  function uris(list) {
    var out = [];
    if (!list || !list.length) return out;
    for (var i = 0; i < list.length && out.length < NEIGHBOURS; i++) {
      var u = list[i] && text(list[i].uri);
      if (u) out.push(u);
    }
    return out;
  }

  function artists(list) {
    var out = [];
    if (!list || !list.length) return out;
    for (var i = 0; i < list.length && out.length < ARTISTS_MAX; i++) {
      var n = list[i] && text(list[i].name);
      if (n) out.push(n);
    }
    return out;
  }

  // The SDK's WebPlaybackState, compacted to what SdkState.kt reads (its KEYS).
  function compact(s) {
    if (!s) return null;
    var w = s.track_window || {};
    var t = w.current_track || {};
    return {
      paused: !!s.paused,
      position: num(s.position),
      duration: num(s.duration),
      uri: text(t.uri),
      linked: t.linked_from ? text(t.linked_from.uri) : null,
      type: text(t.type),
      name: text(t.name),
      artists: artists(t.artists),
      next: uris(w.next_tracks),
      prev: uris(w.previous_tracks),
      context: s.context ? text(s.context.uri) : null
    };
  }

  function post(s) {
    var c = compact(s);
    try { bridge.state(JSON.stringify(c)); } catch (e) { /* the host is gone */ }
    if (c && !c.paused) {
      if (!tick) tick = setInterval(poll, TICK_MS);
    } else if (tick) {
      clearInterval(tick);
      tick = null;
    }
  }

  function poll() {
    if (player) player.getCurrentState().then(post);
  }

  function report(kind, message) {
    try { bridge.error(kind, String(message || '').slice(0, TEXT_MAX)); } catch (e) { /* the host is gone */ }
  }

  function start() {
    if (player || !sdkReady || wanted === null) return;
    player = new window.Spotify.Player({
      name: wanted,
      getOAuthToken: function (cb) {
        var id = nextRequest++;
        pending[id] = cb;
        bridge.needToken(id);
      },
      volume: 1.0
    });
    player.addListener('ready', function (d) { bridge.ready(String(d && d.device_id)); });
    player.addListener('not_ready', function (d) { bridge.notReady(String(d && d.device_id)); });
    player.addListener('player_state_changed', post);
    player.addListener('autoplay_failed', function () { report('autoplay', ''); });
    player.addListener('initialization_error', function (e) { report('initialization', e && e.message); });
    player.addListener('authentication_error', function (e) { report('authentication', e && e.message); });
    player.addListener('account_error', function (e) { report('account', e && e.message); });
    player.addListener('playback_error', function (e) { report('playback', e && e.message); });
    player.connect();
    if (player.activateElement) player.activateElement();
  }

  // The SDK calls this once its script has run.
  window.onSpotifyWebPlaybackSDKReady = function () {
    sdkReady = true;
    start();
  };

  // A script that failed to load (the SDK's: offline, a blocked host) - no inline onerror needed.
  window.addEventListener('error', function (e) {
    var target = e && e.target;
    if (target && target.tagName === 'SCRIPT') report('script', 'the SDK script did not load');
  }, true);

  window.vibb = {
    connect: function (name) {
      wanted = String(name).slice(0, 64);
      start();
    },
    token: function (requestId, token) {
      var cb = pending[requestId];
      delete pending[requestId];
      if (cb) cb(typeof token === 'string' ? token : null);
    },
    pause: function () {
      if (player) player.pause();
    },
    resume: function () {
      if (!player) return;
      if (player.activateElement) player.activateElement();
      player.resume();
    },
    seek: function (ms) {
      if (player) player.seek(num(ms));
    },
    next: function () {
      if (player) player.nextTrack();
    },
    previous: function () {
      if (player) player.previousTrack();
    },
    volume: function (v) {
      var x = Number(v);
      if (player) player.setVolume(isFinite(x) ? Math.min(1, Math.max(0, x)) : 1);
    },
    state: function () {
      if (player) player.getCurrentState().then(post);
      else post(null);
    },
    disconnect: function () {
      if (tick) clearInterval(tick);
      tick = null;
      pending = {};
      if (player) player.disconnect();
      player = null;
    }
  };
})();
