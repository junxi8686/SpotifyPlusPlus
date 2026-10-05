package android.net;

import java.util.Objects;

/**
 * JVM stand-in for {@link android.net.Uri}, used only by the unit-test source set.
 *
 * <p>Needed for the same reason as the test-source {@code android.os.Bundle}: the Android Auto
 * provider holds its authority as a {@code static final Uri URI = Uri.parse(...)} field, and
 * touching any static member of that class (the transport tests call its row-key sanitiser) runs
 * the initialiser. Without this stand-in the stubbed {@code android.jar} throws
 * {@code RuntimeException("Method parse in android.net.Uri not mocked")} and the whole class fails
 * to initialise.
 *
 * <p>Only {@code parse} and value semantics are implemented. Nothing in the JVM tests resolves or
 * grants the URI; the constant exists so the provider has one authority string in one place. Real
 * URI parsing is a platform concern that these tests deliberately do not exercise.
 */
public final class Uri {
    private final String value;

    private Uri(String value) {
        this.value = value;
    }

    public static Uri parse(String uriString) {
        return uriString == null ? null : new Uri(uriString);
    }

    public String getScheme() {
        int colon = value.indexOf(':');
        return colon < 0 ? null : value.substring(0, colon);
    }

    public String getAuthority() {
        int start = value.indexOf("//");
        if (start < 0) return null;
        start += 2;
        int end = value.length();
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return value.substring(start, end);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Uri && Objects.equals(value, ((Uri) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
