package tsembed;

/** Stand-in for the gomobile-generated tsembed.Client - see {@link Tsembed}. */
public final class Client {
    Client() {}

    public String up(long timeoutSeconds) throws Exception {
        throw new Exception("tsnet is not available in this build (libs/tsnet.aar was missing)");
    }

    public String startProxy() throws Exception {
        throw new Exception("tsnet is not available in this build (libs/tsnet.aar was missing)");
    }

    public String proxyCredential() {
        return "";
    }

    public void close() throws Exception {}
}
