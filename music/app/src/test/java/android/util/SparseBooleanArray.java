package android.util;

import java.util.Arrays;

/*
 * A JVM test's stand-in for android.util.SparseBooleanArray (GPL-3.0-or-later, this project's own), with the
 * platform's behaviour (sorted keys, binary search): AGP's mockable android.jar answers false and 0 to
 * everything, so Media3's `Player.Commands` (a FlagSet over this class) would hold no command and a
 * SimpleBasePlayer would ignore every call. With [android.os.Looper] and [Pair] beside it,
 * SpotifyRendererTest drives the session's player through Media3's Player API (emulator check §6l A, F1).
 * Test sources only - never in an APK.
 */
public class SparseBooleanArray implements Cloneable {
    private int[] keys;
    private boolean[] values;
    private int size;

    public SparseBooleanArray() {
        this(10);
    }

    public SparseBooleanArray(int initialCapacity) {
        keys = new int[Math.max(initialCapacity, 1)];
        values = new boolean[keys.length];
    }

    @Override
    public SparseBooleanArray clone() {
        SparseBooleanArray c = new SparseBooleanArray(keys.length);
        c.keys = keys.clone();
        c.values = values.clone();
        c.size = size;
        return c;
    }

    public boolean get(int key) {
        return get(key, false);
    }

    public boolean get(int key, boolean valueIfKeyNotFound) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        return i < 0 ? valueIfKeyNotFound : values[i];
    }

    public void delete(int key) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        if (i >= 0) removeAt(i);
    }

    public void removeAt(int index) {
        System.arraycopy(keys, index + 1, keys, index, size - (index + 1));
        System.arraycopy(values, index + 1, values, index, size - (index + 1));
        size--;
    }

    public void put(int key, boolean value) {
        int i = Arrays.binarySearch(keys, 0, size, key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        i = ~i;
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        System.arraycopy(keys, i, keys, i + 1, size - i);
        System.arraycopy(values, i, values, i + 1, size - i);
        keys[i] = key;
        values[i] = value;
        size++;
    }

    public int size() {
        return size;
    }

    public int keyAt(int index) {
        return keys[index];
    }

    public boolean valueAt(int index) {
        return values[index];
    }

    public void setValueAt(int index, boolean value) {
        values[index] = value;
    }

    public int indexOfKey(int key) {
        return Arrays.binarySearch(keys, 0, size, key);
    }

    public int indexOfValue(boolean value) {
        for (int i = 0; i < size; i++) if (values[i] == value) return i;
        return -1;
    }

    public void clear() {
        size = 0;
    }

    public void append(int key, boolean value) {
        put(key, value);
    }

    @Override
    public int hashCode() {
        int h = size;
        for (int i = 0; i < size; i++) h = 31 * h + keys[i] | (values[i] ? 1 : 0);
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SparseBooleanArray)) return false;
        SparseBooleanArray other = (SparseBooleanArray) o;
        if (size != other.size) return false;
        for (int i = 0; i < size; i++) {
            if (keys[i] != other.keys[i] || values[i] != other.values[i]) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < size; i++) {
            if (i > 0) b.append(", ");
            b.append(keys[i]).append('=').append(values[i]);
        }
        return b.append('}').toString();
    }
}
