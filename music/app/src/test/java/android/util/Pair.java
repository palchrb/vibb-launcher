package android.util;

import java.util.Objects;

/*
 * A JVM test's stand-in for android.util.Pair (GPL-3.0-or-later, this project's own), with the platform's
 * behaviour: AGP's mockable android.jar makes `Pair.create` answer null, which Media3's Timeline reads at
 * every state change of a SimpleBasePlayer. With [android.os.Looper]'s stand-in beside it,
 * SpotifyRendererTest drives the session's player through Media3's Player API (emulator check §6l A, F1).
 * Test sources only - never in an APK.
 */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pair)) return false;
        Pair<?, ?> p = (Pair<?, ?>) o;
        return Objects.equals(p.first, first) && Objects.equals(p.second, second);
    }

    @Override
    public int hashCode() {
        return (first == null ? 0 : first.hashCode()) ^ (second == null ? 0 : second.hashCode());
    }

    @Override
    public String toString() {
        return "Pair{" + first + " " + second + "}";
    }
}
