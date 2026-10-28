package com.spotifyplusplus.settings;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.settings.values.StoredValue;
import com.spotifyplusplus.settings.values.StoredValues;

/**
 * Reads a setting's current value, already resolved for the current orientation.
 *
 * <p>Implemented by the runtime config, the panel store and the captured settings snapshot, so
 * one typed read works the same on every surface.
 */
public interface SettingsReader {
    <T> T get(Settings.Setting<T> setting);

    /**
     * The value a stored-label setting holds. An unknown or absent label reads as the value's
     * default, exactly as an uncoerced read of the same setting does today.
     */
    default <E extends Enum<E> & StoredValue> E get(Settings.Setting<String> setting, Class<E> type) {
        return StoredValues.fromStored(type, get(setting));
    }
}
