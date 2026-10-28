package com.spotifyplusplus.settings.panel;

import android.widget.EditText;
import android.widget.TextView;

import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SettingsStore;
import com.spotifyplusplus.settings.SettingsWriter;
import com.spotifyplusplus.settings.registry.UiContext;
import com.spotifyplusplus.ui.SettingsUiStrings;
import com.spotifyplusplus.lyrics.processing.BaiduTranslateCredentials;
import com.spotifyplusplus.lyrics.providers.LyricsResponseCache;
import com.spotifyplusplus.lyrics.cache.CacheStoragePolicy;
import com.spotifyplusplus.lyrics.providers.SpicyManualTokenStore;
import com.spotifyplusplus.lyrics.providers.SpicyOrgKeyStore;
import com.spotifyplusplus.lyrics.session.AIPaidArtifactCache;
import com.spotifyplusplus.lyrics.session.CanonicalSourceCache;
import com.spotifyplusplus.ui.ActionIconDrawable;
import com.spotifyplusplus.ui.PanelDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Every dialog the settings panel opens, in one place.
 *
 * <p>Owns the option list (labels, availability reasons, previews, icons), the confirming
 * selector, the cache breakdown, and the Spicy manual-token flow. Writing is delegated: the
 * host performs the store write and decides what to rebuild, so no dialog invents its own
 * persistence path and no write ever happens from a dismiss callback.
 */
public final class PanelDialogs {
    /** What the dialogs need from the panel. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        /** Writes the value and applies immediate side effects (the UI-language swap). */
        void onOptionChosen(Settings.StringSetting setting, String value);

        /** Post-dismiss structural refresh for a changed setting. */
        void afterSettingChosen(Settings.Setting<?> setting);

        String labelFor(Settings.StringSetting setting, String value);

        /** Row summary text; the cache-size row carries a usage suffix the plain label lacks. */
        String rowSummaryFor(Settings.StringSetting setting, String value);

        /** The applied-state snapshot the panel renders from. */
        UiContext uiContext();

        PanelStrings panelStrings();

        /** The Spicy Lyrics client key changed; refresh the owning section. */
        void onSpicyKeyChanged();

        /** The Spicy manual token changed; refresh the owning section. */
        void onSpicyTokenChanged();

        /** A Baidu Translate credential changed; refresh the Translation section. */
        void onBaiduCredentialsChanged();
    }

    private final Host host;

    public PanelDialogs(Host host) {
        this.host = host;
    }

    /** Ordinary single-select option dialog; confirming settings route to their own path. */
    public void openSelector(Settings.StringSetting setting, List<String> values, TextView valueView) {
        if (PanelPolicy.commitPolicyFor(setting) == CommitPolicy.CONFIRMING) {
            openConfirmingSelector(setting, values, valueView);
            return;
        }
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String current = host.store().get(setting);
        final UiContext context = host.uiContext();
        // Cache size only: recompute once per dialog so the selected option carries the usage suffix.
        final String usageSuffix = setting == Settings.CACHE_SIZE
                ? " · " + CacheStoragePolicy.formatBytes(
                CacheStoragePolicy.storedTotal(style.context())) + " used"
                : null;
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            PanelDialog.Option option = PanelDialog.Option.of(val, host.labelFor(setting, val));
            String reason = PanelPolicy.optionUnavailableReason(setting, val, context, host.panelStrings());
            if (!reason.isEmpty()) {
                option.unavailable = true;
                option.suffix = "  · " + reason;
            } else if (usageSuffix != null && val.equals(current)) {
                option.suffix = usageSuffix;
            }
            option.preview = SettingLabels.optionPreview(setting.key, val);
            if (setting == Settings.LIKED_SONGS_BUTTON) {
                option.icon = ActionIconDrawable.likedSongsKind(val);
            }
            options.add(option);
        }
        PanelDialog dialog = new PanelDialog(style.context(), strings.setting(setting));
        if (setting == Settings.CACHE_SIZE) {
            dialog.headerAction(ActionIconDrawable.Kind.CIRCLE_HELP,
                    strings.get("settings_cache_details_action", "Show cache details"),
                    this::showCacheInfo);
        }
        dialog.listOptions(options, current, value -> {
            // Selecting the already-applied value is a true no-op. In particular,
            // opening and dismissing the language picker must not rebuild the panel.
            if (value.equals(current)) return null;
            host.onOptionChosen(setting, value);
            valueView.setText(host.rowSummaryFor(setting, value));
            // The store write is already committed; the structural rebuild waits for the
            // dismissal animation so a language change does not re-render under the dialog.
            return () -> host.afterSettingChosen(setting);
        });
        dialog.show();
    }

    /**
     * Confirming selector. Tapping a row only moves the pending highlight; nothing is
     * written until Save. Cancel, back, and outside-tap discard.
     */
    public void openConfirmingSelector(Settings.StringSetting setting, List<String> values,
                                       TextView valueView) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String initial = host.store().get(setting);
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            options.add(PanelDialog.Option.of(val, host.labelFor(setting, val)));
        }
        new PanelDialog(style.context(), strings.setting(setting))
                .confirmingOptions(options, initial, selected -> {
                    if (selected != null && !selected.equals(initial)) {
                        host.onOptionChosen(setting, selected);
                        valueView.setText(host.rowSummaryFor(setting, selected));
                        host.afterSettingChosen(setting);
                    }
                }, strings.get("settings_ai_save", "Save"),
                        strings.get("settings_ai_cancel", "Cancel"))
                .show();
    }

    public void showCacheInfo() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        long aiBytes = AIPaidArtifactCache.usageBytes(style.context());
        new PanelDialog(style.context(), strings.get("settings_cache_details_title", "Cache details"))
                .infoRow(strings.get("settings_cache_details_songs", "Cached songs"),
                        String.valueOf(Math.max(
                                CanonicalSourceCache.entryCount(style.context()),
                                LyricsResponseCache.entryCount(style.context()))))
                .infoRow(strings.get("settings_cache_details_lyrics", "Lyric data cached"),
                        CacheStoragePolicy.formatBytes(
                                CacheStoragePolicy.storedTotal(style.context()) - aiBytes))
                .infoRow(strings.get("settings_cache_details_ai", "AI data cached"),
                        CacheStoragePolicy.formatBytes(aiBytes))
                .secondary(strings.get("settings_cache_details_close", "Close"), null)
                .show();
    }

    public void promptSpicyKey() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_ORG_CLIENT_KEY)).secure();
        dialog.paragraph(strings.get("settings_spicy_key_help",
                "Add Spicy EX from the catalog. Paste your personal client key. Save the key. Enable SpicyLyrics.org in Lyrics source."));
        EditText field = dialog.field(true, "");
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (SpicyOrgKeyStore.save(style.context(), field.getText().toString().trim())) {
                host.onSpicyKeyChanged();
            } else {
                android.widget.Toast.makeText(style.context(),
                        strings.get("settings_spicy_key_rejected", "The key is not saved. Use a client key that starts with sl_pk_."),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    /**
     * The desktop-captured Spotify token the retired Spicy source used.
     *
     * <p>Fork-only: upstream retired this row, this tree still stores and backs the value up, so
     * there has to be a way to enter it. Entered masked and stored encrypted like every other
     * credential; the plaintext only ever appears in the secure reveal dialog.
     */
    public void promptSpicyToken() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_MANUAL_TOKEN)).secure();
        EditText field = dialog.field(true, "");
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (SpicyManualTokenStore.save(style.context(), field.getText().toString().trim())) {
                host.onSpicyTokenChanged();
            } else {
                toast(style, strings.get("settings_spicy_token_rejected", "Token not saved"));
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    public void revealSpicyToken() {
        PanelStyle style = host.style();
        String token = SpicyManualTokenStore.load(style.context());
        if (token.isEmpty()) return;
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_MANUAL_TOKEN))
                .secure().closeIcon(strings.get("lyrics_ai_close", "Close"));
        dialog.secretValue(token);
        dialog.show();
    }

    /**
     * Baidu Translate app id. Baidu's official API needs both halves of a credential pair that only
     * the owner's own account has, so the dialog says where they come from instead of only
     * rejecting a value: a secret pasted from anywhere else cannot work.
     */
    public void promptBaiduAppId() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.BAIDU_TRANSLATE_APPID));
        dialog.paragraph(strings.get("settings_baidu_help",
                "Get an app ID and secret from your Baidu translate account: "
                        + BaiduTranslateCredentials.CONSOLE_URL));
        EditText field = dialog.field(false, BaiduTranslateCredentials.appId(style.context()));
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (BaiduTranslateCredentials.saveAppId(style.context(),
                    field.getText().toString().trim())) {
                host.onBaiduCredentialsChanged();
            } else {
                toast(style, strings.get("settings_baidu_appid_rejected",
                        "App ID not saved: expected letters and digits"));
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    /** The secret half. Entered masked, stored encrypted, and shown only by the reveal dialog. */
    public void promptBaiduSecret() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.BAIDU_TRANSLATE_SECRET)).secure();
        dialog.paragraph(strings.get("settings_baidu_help",
                "Get an app ID and secret from your Baidu translate account: "
                        + BaiduTranslateCredentials.CONSOLE_URL));
        EditText field = dialog.field(true, "");
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (BaiduTranslateCredentials.saveSecret(style.context(),
                    field.getText().toString().trim())) {
                host.onBaiduCredentialsChanged();
            } else {
                toast(style, strings.get("settings_baidu_secret_rejected",
                        "Secret not saved: expected the key Baidu issued"));
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    public void revealBaiduAppId() {
        revealBaiduCredential(Settings.BAIDU_TRANSLATE_APPID,
                BaiduTranslateCredentials.appId(host.style().context()));
    }

    public void revealBaiduSecret() {
        revealBaiduCredential(Settings.BAIDU_TRANSLATE_SECRET,
                BaiduTranslateCredentials.secret(host.style().context()));
    }

    /** Plaintext exists only in a secure, non-selectable, accessibility-hidden transient dialog. */
    private void revealBaiduCredential(Settings.Setting<?> setting, String value) {
        if (value == null || value.isEmpty()) return;
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(), strings.setting(setting))
                .secure().closeIcon(strings.get("lyrics_ai_close", "Close"));
        dialog.secretValue(value);
        dialog.show();
    }

    private void toast(PanelStyle style, String message) {
        if (message == null || message.isEmpty()) return;
        android.widget.Toast.makeText(style.context(), message, android.widget.Toast.LENGTH_SHORT)
                .show();
    }

}
