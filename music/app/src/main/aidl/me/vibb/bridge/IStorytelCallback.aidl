// The Storytel login, answered by the launcher (design 21 §2). Byte-identical in launcher/ and music/.
package me.vibb.bridge;

interface IStorytelCallback {
    /** [generation] 0 = no login (delete the stored one); email and password are then null. */
    oneway void onLogin(long generation, String email, String password);
}
