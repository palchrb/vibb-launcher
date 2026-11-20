// The web player in its own process (design 120 §4.2, revision 2): the main process -> the `:web` process. Private to
// the music app (SpotifyWebService is not exported) - not the launcher bridge (me/vibb/bridge/, bridge_contract.json).
// Every call is one-way; the page's own calls are typed here, never a script or generic JSON (design 120 QA L4).
package me.vibb.music.spotify.web;

import me.vibb.music.spotify.web.ISpotifyWebEvents;

oneway interface ISpotifyWeb {
    /** Make this process's one page: [fakeBase] the debug switch's base (null in a release), [clearFirst] the stores
     *  cleared before it loads (an account change, design 26c §5.2), [events] where the page's events go. A process
     *  that already had a page answers `stale` and ends. */
    void open(String fakeBase, boolean clearFirst, ISpotifyWebEvents events);

    /** The SDK's `getOAuthToken` answered: [token] null for none. Never logged on either side. */
    void token(int requestId, String token);

    void seek(long positionMs);

    void volume(double level);

    /** One of the page's calls without an argument: pause, resume, next, previous, state. */
    void command(String name);

    /** The SDK's disconnect, the page destroyed, and the process ends itself (one page per process). */
    void destroy();
}
