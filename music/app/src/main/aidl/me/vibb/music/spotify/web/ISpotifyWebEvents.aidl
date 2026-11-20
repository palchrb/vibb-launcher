// The web player in its own process (design 120 §4.2, revision 2): the `:web` process -> the main process. Every event
// is checked in the web process first (PageBridge: the device id, the request id, the 8 KB state cap, the error kinds)
// and sent from its main looper, so the binder keeps the page's order.
package me.vibb.music.spotify.web;

oneway interface ISpotifyWebEvents {
    void ready(String deviceId);

    void notReady(String deviceId);

    void needToken(int requestId);

    /** A posted state, the page's compact JSON (at most SdkState.MAX_BYTES). */
    void state(String json);

    void error(String kind, String message);

    /** One fixed-words log line of the web process (the main process's log gate decides). */
    void log(String line);

    /** The page's renderer died: the process ends itself. */
    void gone();

    /** This process already had a page: refused - bind a fresh one once it has ended. */
    void stale();
}
