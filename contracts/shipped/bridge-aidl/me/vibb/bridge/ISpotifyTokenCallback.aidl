// The Spotify token, answered by the launcher (design 26c §6.1). Byte-identical in launcher/ and music/.
package me.vibb.bridge;

interface ISpotifyTokenCallback {
    /** [status]: one of bridge_contract.json's spotify_token.statuses; [accessToken] only with "ok", else null;
     *  [expiresInMs] its life from now with "ok", the wait before asking again with "busy" (the server's
     *  retry_after_s), else 0; [account] "<id>:<generation>" with "ok", else null. */
    oneway void onToken(String status, String accessToken, long expiresInMs, String account);
}
