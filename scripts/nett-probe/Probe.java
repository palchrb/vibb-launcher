package probe.nett;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Process;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Design 127 step 0: the Nett probe's target app. `am broadcast -f 32 -n <pkg>/probe.nett.Probe --es what basic|bind|dm`. */
public class Probe extends BroadcastReceiver {
    static final String TAG = "NettProbeApp";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String what = intent.getStringExtra("what") == null ? "basic" : intent.getStringExtra("what");
        final PendingResult pending = goAsync();
        new Thread(() -> {
            String r;
            try {
                if (what.equals("bind")) r = bind(context);
                else if (what.equals("dm")) r = dm(context);
                else if (what.equals("hold")) r = hold(intent.getIntExtra("secs", 12));
                else r = basic(context);
            } catch (Throwable t) {
                r = "FAILED " + t;
            }
            Log.i(TAG, context.getPackageName() + " uid=" + Process.myUid() + " " + what + ": " + r);
            pending.setResultData(r);
            pending.finish();
        }).start();
    }

    interface Step { String run() throws Exception; }

    static String attempt(Step s) {
        long t = System.currentTimeMillis();
        try {
            return s.run() + "(" + (System.currentTimeMillis() - t) + "ms)";
        } catch (Throwable e) {
            String m = e.getMessage();
            return "FAIL(" + e.getClass().getSimpleName() + ": " + (m == null ? "" : m.substring(0, Math.min(70, m.length()))) + "; " + (System.currentTimeMillis() - t) + "ms)";
        }
    }

    static String basic(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network a = cm.getActiveNetwork();
        NetworkCapabilities caps = a == null ? null : cm.getNetworkCapabilities(a);
        List<String> p = new ArrayList<>();
        p.add("active=" + a + (caps == null ? "" : " vpn=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) + " wifi=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) + " cell=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)));
        p.add("blocked=" + (a == null ? "n/a" : String.valueOf(cm.getNetworkInfo(a) == null ? "null-info" : cm.getNetworkInfo(a).getDetailedState())));
        p.add("tcp=" + attempt(() -> { try (Socket s = new Socket()) { s.connect(new InetSocketAddress("1.1.1.1", 443), 4000); } return "ok"; }));
        p.add("dns=" + attempt(() -> InetAddress.getAllByName("example.org")[0].getHostAddress()));
        p.add("http=" + attempt(() -> {
            HttpURLConnection h = (HttpURLConnection) new URL("https://example.com/").openConnection();
            h.setConnectTimeout(4000); h.setReadTimeout(4000);
            try { return "" + h.getResponseCode(); } finally { h.disconnect(); }
        }));
        return String.join(" ", p);
    }

    static String bind(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        List<String> p = new ArrayList<>();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            String kind = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell" : "other";
            p.add(kind + n + ".bindSocket=" + attempt(() -> { try (Socket s = new Socket()) { n.bindSocket(s); s.connect(new InetSocketAddress("1.1.1.1", 443), 4000); } return "ok"; }));
            p.add(kind + n + ".openConnection=" + attempt(() -> {
                HttpURLConnection h = (HttpURLConnection) n.openConnection(new URL("https://1.1.1.1/"));
                h.setConnectTimeout(4000); h.setReadTimeout(4000);
                try { return "" + h.getResponseCode(); } finally { h.disconnect(); }
            }));
            p.add(kind + n + ".bindProcess=" + attempt(() -> {
                boolean ok = cm.bindProcessToNetwork(n);
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress("1.1.1.1", 443), 4000); } finally { cm.bindProcessToNetwork(null); }
                return "bound=" + ok + " ok";
            }));
        }
        return p.isEmpty() ? "no non-VPN networks" : String.join(" ", p);
    }

    /** Opens a TLS connection, waits, then sends a request over it: does an open connection survive what happens meanwhile? */
    static String hold(int secs) {
        return attempt(() -> {
            javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) javax.net.ssl.SSLSocketFactory.getDefault().createSocket();
            try {
                s.connect(new InetSocketAddress("example.com", 443), 4000);
                s.startHandshake();
                Log.i(TAG, "hold: connected, waiting " + secs + " s");
                Thread.sleep(secs * 1000L);
                s.setSoTimeout(5000);
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".getBytes());
                s.getOutputStream().flush();
                byte[] b = new byte[64];
                int n = s.getInputStream().read(b);
                return n <= 0 ? "closed" : new String(b, 0, Math.min(n, 15));
            } finally {
                s.close();
            }
        });
    }

    static String dm(Context c) throws Exception {
        DownloadManager d = c.getSystemService(DownloadManager.class);
        long id = d.enqueue(new DownloadManager.Request(Uri.parse("https://example.com/")));
        String last = "?";
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            try (Cursor q = d.query(new DownloadManager.Query().setFilterById(id))) {
                if (q.moveToFirst()) {
                    int st = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int re = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    long got = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    last = "status=" + st + " reason=" + re + " bytes=" + got + " after " + (i + 1) + "s";
                    if (st == DownloadManager.STATUS_SUCCESSFUL || st == DownloadManager.STATUS_FAILED) break;
                }
            }
        }
        d.remove(id);
        return last;
    }
}
