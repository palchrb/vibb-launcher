package tsembed;

/**
 * Stand-in for the gomobile-generated binding in libs/tsnet.aar, compiled only when that aar is
 * absent (see app/build.gradle.kts). Lets the launcher build and run its unit tests on machines
 * without the Go/NDK toolchain. Same signatures as the generated code for mobile/tsembed; every
 * connection attempt fails, so the app behaves as if the tailnet were unreachable and falls back
 * to the direct server connection.
 */
public abstract class Tsembed {
    private Tsembed() {}

    public static Client new_(String hostname, String authKey, String stateDir) {
        return new Client();
    }
}
