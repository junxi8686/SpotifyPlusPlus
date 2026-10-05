package android.os;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JVM stand-in for {@link android.os.Bundle}, used only by the unit-test source set.
 *
 * <p>Android's unit tests run against a stubbed {@code android.jar} whose methods throw
 * {@code RuntimeException("Method ... not mocked")}. This project enables no Robolectric
 * dependency, so any test that round-trips a {@code Bundle} could not run at all: the Android
 * Auto transport tests build request and reply packets and assert on what survives sanitisation,
 * which is exactly the behaviour that needs covering.
 *
 * <p>This class is deliberately NOT part of the ported upstream code and is NOT shipped: it lives
 * under {@code app/src/test/java}, so it is compiled only into the test runtime classpath, where it
 * shadows the stub. Only the members the Android Auto code actually calls are implemented, and the
 * defaults match the platform's documented contract (a missing key yields the caller's default, or
 * null/0/false for the single-argument forms). {@code Parcelable} is implemented because Android's
 * real {@code Bundle} is itself {@code Parcelable}, and the transport stores
 * {@code ArrayList<Bundle>} through {@code putParcelableArrayList}.
 *
 * <p>Because this is a hand-written stand-in rather than the platform class, it cannot prove
 * anything about real parceling; it only proves the transport's own key handling, defaults,
 * validation and session-merge rules.
 */
public final class Bundle implements Parcelable, Cloneable {
    private final Map<String, Object> values;

    public Bundle() {
        this.values = new LinkedHashMap<>();
    }

    public Bundle(Bundle other) {
        this.values = new LinkedHashMap<>();
        if (other != null) this.values.putAll(other.values);
    }

    public void putAll(Bundle other) {
        if (other != null) values.putAll(other.values);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public Set<String> keySet() {
        return new java.util.LinkedHashSet<>(values.keySet());
    }

    public void remove(String key) {
        values.remove(key);
    }

    public void clear() {
        values.clear();
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    public void putInt(String key, int value) {
        values.put(key, value);
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    public void putLong(String key, long value) {
        values.put(key, value);
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defaultValue;
    }

    public void putDouble(String key, double value) {
        values.put(key, value);
    }

    public double getDouble(String key) {
        return getDouble(key, 0d);
    }

    public double getDouble(String key, double defaultValue) {
        Object value = values.get(key);
        return value instanceof Double ? (Double) value : defaultValue;
    }

    public void putFloat(String key, float value) {
        values.put(key, value);
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public float getFloat(String key, float defaultValue) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    public void putIntArray(String key, int[] value) {
        values.put(key, value == null ? null : value.clone());
    }

    public int[] getIntArray(String key) {
        Object value = values.get(key);
        return value instanceof int[] ? ((int[]) value).clone() : null;
    }

    public void putStringArrayList(String key, ArrayList<String> value) {
        values.put(key, value == null ? null : new ArrayList<>(value));
    }

    @SuppressWarnings("unchecked")
    public ArrayList<String> getStringArrayList(String key) {
        Object value = values.get(key);
        return value instanceof ArrayList ? new ArrayList<>((ArrayList<String>) value) : null;
    }

    public void putParcelableArrayList(String key, ArrayList<? extends Parcelable> value) {
        values.put(key, value == null ? null : new ArrayList<>(value));
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String key) {
        Object value = values.get(key);
        return value instanceof ArrayList ? new ArrayList<>((ArrayList<T>) value) : null;
    }

    public void putBundle(String key, Bundle value) {
        values.put(key, value);
    }

    public Bundle getBundle(String key) {
        Object value = values.get(key);
        return value instanceof Bundle ? (Bundle) value : null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        // Test-only stand-in: no real parceling happens in a JVM unit test.
    }

    @Override
    public Bundle clone() {
        return new Bundle(this);
    }

    /** Mirrors the platform's {@code Bundle[{key=value, ...}]} diagnostic form. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Bundle[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) text.append(", ");
            first = false;
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.append(']').toString();
    }
}
