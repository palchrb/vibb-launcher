// The bridge between the launcher and the Vibb music app (design 21 §2, 21b §5). Byte-identical in
// launcher/ and music/ (both test suites compare); new methods go at the end (the transaction codes
// follow the order). The launcher implements it (MusicBridgeService, a signature permission); the
// music app calls it off the main thread.
package me.vibb.bridge;

import me.vibb.bridge.IStorytelCallback;

interface IMusicBridge {
    /** The bridge's version: 1. */
    int version();

    /** The library the launcher holds, read-only, or null when it is [haveVersion] or there is none. */
    ParcelFileDescriptor openLibrary(String haveVersion);

    /** A cover by its SHA-256, or null when the launcher hasn't got it. */
    ParcelFileDescriptor openCover(String hash);

    /** An own file the launcher downloaded and hasn't handed over yet, or null. */
    ParcelFileDescriptor openFile(long id);

    /** The music app has its copy of file [id] (checked against [sha256]): the launcher deletes its own. */
    oneway void releaseFile(long id, String sha256);

    /** The music app lacks these files (wiped data, a reinstall): the launcher fetches them again. */
    oneway void wantFiles(in long[] ids);

    /** The Storytel login, through [callback], when the launcher's generation isn't [haveGeneration]. */
    oneway void getStorytelLogin(long haveGeneration, IStorytelCallback callback);

    /** The music app's state for the status report (`music_state`): key "report", a JSON object. */
    oneway void reportState(in Bundle state);

    /** What plays (for Home and the lock, never the server); null when nothing does. */
    oneway void setNowPlaying(in Bundle nowPlaying);

    /** The player is (not) the top resumed activity; [token] dies with the music app's process. */
    oneway void setForeground(IBinder token, boolean inFront);

    /** The version of the entry's listing the launcher holds, or null when it has none. */
    String listingVersion(long entryId);

    /** The entry's listing, read-only, or null when the launcher has none. */
    ParcelFileDescriptor openListing(long entryId);
}
