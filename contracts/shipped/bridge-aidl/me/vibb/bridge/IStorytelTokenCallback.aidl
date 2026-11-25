// The Storytel session, answered by the launcher (design 58 §4). Byte-identical in launcher/ and music/.
package me.vibb.bridge;

interface IStorytelTokenCallback {
    /** [status]: one of bridge_contract.json's storytel_token.statuses; [jwt] only with "ok", else null;
     *  [expiresInMs] its use left from now with "ok", the wait before asking again with "refused" and "busy" (the
     *  server's retry_after_s), else 0; [session] "<generation>:<16 hex>" with "ok", else null. */
    oneway void onToken(String status, String jwt, long expiresInMs, String session);
}
