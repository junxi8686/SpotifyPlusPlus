package com.spotifyplusplus.settings.panel;

import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.spotifyplusplus.ui.GlossyToggle;
import com.spotifyplusplus.Settings;
import com.spotifyplusplus.SettingsStore;
import com.spotifyplusplus.settings.SettingsWriter;
import com.spotifyplusplus.settings.registry.ControlSpec;
import com.spotifyplusplus.settings.registry.SliderSpec;
import com.spotifyplusplus.ui.SettingsUiStrings;
import com.spotifyplusplus.ui.ActionIconDrawable;
import com.spotifyplusplus.ui.ActionIconDrawable.Kind;

import java.util.List;

/**
 * Builds every ordinary settings row and patches one in place on rebind.
 *
 * <p>One renderer per registry row kind, so adding a setting of an existing kind needs no new
 * branch anywhere. Composite rows (source ordering, credentials, diagnostics) are built by their
 * own owners and never arrive here.
 *
 * <p>The factory owns no panel state. Values come from the store, labels from the host's
 * locale, and every user edit is handed back through {@link Host} so the panel keeps the single
 * decision about what to rebuild.
 */
public final class SettingRowFactory {
    /** What rows need from the panel: values, locale, and the callbacks that own rebuilds. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        /** A value for this setting changed; the panel decides whether to rebuild a section. */
        void onSettingChanged(Settings.Setting<?> setting);

        /** Opens the option dialog for a selector row. */
        void openSelector(Settings.StringSetting setting, List<String> values, TextView valueView);

        String labelFor(Settings.StringSetting setting, String value);

        boolean unavailable(Settings.Setting<?> setting);

        String unavailableSummary(Settings.Setting<?> setting);

        String cacheSizeSummary();

        String stepperSummary(Settings.IntegerSetting setting);
    }

    private final Host host;

    public SettingRowFactory(Host host) {
        this.host = host;
    }

    // --- Row construction ---

    public void switchRow(LinearLayout content, final Settings.BooleanSetting setting) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        boolean unavailable = host.unavailable(setting);
        style.titleColumn(row, host.strings().setting(setting),
                switchSummary(setting, unavailable));
        style.applyRowLead(row, setting.key);
        GlossyToggle toggle = new GlossyToggle(style.context());
        toggle.setAccent(PanelStyle.COL_ACCENT);
        toggle.setChecked(!unavailable && host.store().get(setting), false);
        toggle.setEnabled(!unavailable);
        row.setEnabled(!unavailable);
        row.setAlpha(unavailable ? 0.48f : 1f);
        if (!unavailable) {
            toggle.setOnChangeListener(() -> {
                host.writer().put(setting, toggle.isChecked());
                host.onSettingChanged(setting);
            });
            row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked(), true));
        }
        row.addView(toggle);
    }

    private String switchSummary(Settings.Setting<?> setting, boolean unavailable) {
        if (unavailable) return host.unavailableSummary(setting);
        return setting == Settings.AUTO_ENABLED
                ? host.strings().get("settings_auto_root_requirement",
                        "Requires root and LSPosed/Vector. Scope Spotify and Android Auto.")
                : null;
    }

    public void selectorRow(LinearLayout content, final Settings.StringSetting setting) {
        if (setting == Settings.CACHE_SIZE) {
            selectorRow(content, setting, Settings.CACHE_SIZE.allowedValues, host.cacheSizeSummary());
            return;
        }
        selectorRow(content, setting, setting.allowedValues, null);
    }

    public void selectorRow(LinearLayout content, final Settings.StringSetting setting,
                            final List<String> values, String summaryOverride) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        boolean unavailable = host.unavailable(setting);
        String summary = summaryOverride != null ? summaryOverride
                : host.labelFor(setting, host.store().get(setting));
        TextView value = style.titleColumn(row, host.strings().setting(setting),
                unavailable ? host.unavailableSummary(setting) : summary);
        style.applyRowLead(row, setting.key);
        if (!unavailable) value.setTextColor(PanelStyle.COL_ACCENT);
        if (setting == Settings.PIP_SHAPE) addShapePreview(row, setting, value);
        row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                new LinearLayout.LayoutParams(style.dp(24), style.dp(30)));
        row.setEnabled(!unavailable);
        if (!unavailable) row.setOnClickListener(v -> host.openSelector(setting, values, value));
    }

    /** The chosen window shape, drawn: follows the value as it changes (the summary is rewritten
     *  in place when an option is picked, so that is what it listens to). */
    private void addShapePreview(LinearLayout row, Settings.StringSetting setting, TextView value) {
        PanelStyle style = host.style();
        android.widget.ImageView shape = new android.widget.ImageView(style.context());
        shape.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Runnable update = () -> {
            int[] ratio = Settings.pipShapeRatio(host.store().get(setting));
            shape.setImageDrawable(new com.spotifyplusplus.ui.AspectRectDrawable(
                    ratio[0] / (float) ratio[1], PanelStyle.COL_ACCENT, style.density()));
        };
        update.run();
        value.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) { update.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(style.dp(30), style.dp(30));
        lp.rightMargin = style.dp(4);
        row.addView(shape, lp);
    }

    public void stepperRow(LinearLayout content, ControlSpec entry) {
        final Settings.IntegerSetting setting = (Settings.IntegerSetting) entry.setting;
        final String unit = unitSuffix(entry);
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        style.titleColumn(row, host.strings().setting(setting), host.stepperSummary(setting));

        LinearLayout controls = new LinearLayout(style.context());
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);

        View minus = stepButton(false, setting);
        TextView value = style.text(
                SettingLabels.formatStepper(setting, host.store().get(setting), unit),
                15, PanelStyle.COL_ACCENT, true);
        value.setTag(PanelTags.ROW_VALUE);
        value.setGravity(android.view.Gravity.CENTER);
        View plus = stepButton(true, setting);

        controls.addView(minus, new LinearLayout.LayoutParams(style.dp(36), style.dp(36)));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(style.dp(74), style.dp(36));
        valueLp.leftMargin = style.dp(4);
        valueLp.rightMargin = style.dp(4);
        controls.addView(value, valueLp);
        controls.addView(plus, new LinearLayout.LayoutParams(style.dp(36), style.dp(36)));
        row.addView(controls);

        final int[] pending = new int[]{host.store().get(setting)};
        final Runnable commit = () -> {
            host.writer().put(setting, pending[0]);
            // Same notification the switch and selector rows send: the panel decides whether the
            // change needs a rebuild, and the shell's preference listener sees one write shape.
            host.onSettingChanged(setting);
        };
        attachStepperTouch(minus, setting, unit, value, -setting.stepValue, pending, commit);
        attachStepperTouch(plus, setting, unit, value, setting.stepValue, pending, commit);
    }

    /**
     * The panel's drag slider, drawn with the same track/thumb/tick vocabulary as the layout
     * editor's sliders (the owner asked for the line-spacing control, not a stepper).
     *
     * <p>Each movement is a live write through the same {@link SettingsWriter} the other rows use,
     * followed by {@code onSettingChanged} so the panel and the shell see it exactly as they see a
     * toggle or a selector pick. The value label carries the row's registry unit ("62%"), so the
     * percent setting never renders as a duration.
     */
    public void sliderRow(LinearLayout content, ControlSpec entry) {
        final Settings.IntegerSetting setting = (Settings.IntegerSetting) entry.setting;
        final SliderSpec slider = entry.slider != null
                ? entry.slider : SliderSpec.from(setting, null);
        final int min = slider.min;
        final int max = slider.max;
        final int step = Math.max(1, slider.step);
        final String unit = unitSuffix(entry);
        final Integer marker = slider.defaultMarker;
        final boolean unavailable = host.unavailable(setting);
        final int initial = snapToStep(host.store().get(setting), min, max, step);

        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        row.setEnabled(!unavailable);
        row.setAlpha(unavailable ? 0.48f : 1f);
        style.titleColumn(row, host.strings().setting(setting), null);
        style.applyRowLead(row, setting.key);

        final TextView valueLabel = style.text(
                SettingLabels.formatStepper(setting, initial, unit),
                15, PanelStyle.COL_ACCENT, true);
        valueLabel.setTag(PanelTags.ROW_VALUE);
        valueLabel.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        row.addView(valueLabel, new LinearLayout.LayoutParams(style.dp(60),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final int thumbSize = style.dp(22);
        FrameLayout track = new FrameLayout(style.context());
        track.setFocusable(true);
        track.setContentDescription(valueLabel.getText());
        android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0x24FFFFFF);
        trackBg.setCornerRadius(style.dp(3));
        track.setBackground(trackBg);

        View tick = null;
        if (marker != null) {
            tick = new View(style.context());
            tick.setBackgroundColor(0x80FFFFFF);
            tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            track.addView(tick, new FrameLayout.LayoutParams(style.dp(2), style.dp(10),
                    android.view.Gravity.CENTER_VERTICAL));
        }
        final View tickView = tick;

        final View thumb = new View(style.context());
        thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.GradientDrawable thumbBg =
                new android.graphics.drawable.GradientDrawable();
        thumbBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumbBg.setColor(PanelStyle.COL_ACCENT);
        thumb.setBackground(thumbBg);
        final FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                thumbSize, thumbSize,
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        track.addView(thumb, thumbLp);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
        trackLp.leftMargin = style.dp(10);
        row.addView(track, trackLp);

        final int[] value = {initial};
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            int trackW = track.getWidth() - thumbSize;
            if (trackW <= 0) {
                track.post(() -> paint[0].run());
                return;
            }
            float fraction = (value[0] - min) / (float) Math.max(1, max - min);
            thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
            thumb.setLayoutParams(thumbLp);
            if (tickView != null) {
                float detent = (marker - min) / (float) Math.max(1, max - min);
                FrameLayout.LayoutParams tickLp =
                        (FrameLayout.LayoutParams) tickView.getLayoutParams();
                tickLp.leftMargin = thumbSize / 2 - style.dp(1)
                        + Math.round(trackW * clamp01(detent));
                tickView.setLayoutParams(tickLp);
            }
        };
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
        track.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            // The card scrolls: without this a slightly vertical drag hands the gesture to the
            // scroll view instead of finishing the slide here (same rule as the editor).
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (action != android.view.MotionEvent.ACTION_DOWN
                    && action != android.view.MotionEvent.ACTION_MOVE) return false;
            if (unavailable) return false;
            int trackW = track.getWidth() - thumbSize;
            if (trackW <= 0) return false;
            float fraction = clamp01((event.getX() - thumbSize / 2f) / trackW);
            int next = snapToStep(Math.round(min + fraction * (max - min)), min, max, step);
            if (next != value[0]) {
                value[0] = next;
                valueLabel.setText(SettingLabels.formatStepper(setting, next, unit));
                track.setContentDescription(valueLabel.getText());
                host.writer().put(setting, next);
                host.onSettingChanged(setting);
                paint[0].run();
            }
            return true;
        });
        // Reads as a slider to TalkBack, and the scroll actions step by one declared step.
        track.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View hostView,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(hostView, info);
                info.setClassName(android.widget.SeekBar.class.getName());
                info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        min, max, value[0]));
                if (value[0] < max) info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (value[0] > min) info.addAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }

            @Override public boolean performAccessibilityAction(View hostView, int action,
                    android.os.Bundle args) {
                int direction = action
                        == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1
                        : action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 0;
                if (direction == 0) return super.performAccessibilityAction(hostView, action, args);
                int next = clamp(value[0] + direction * step, min, max);
                if (next == value[0]) return true;
                value[0] = next;
                valueLabel.setText(SettingLabels.formatStepper(setting, next, unit));
                track.setContentDescription(valueLabel.getText());
                host.writer().put(setting, next);
                host.onSettingChanged(setting);
                paint[0].run();
                hostView.sendAccessibilityEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
                return true;
            }
        });
    }

    /**
     * The unit suffix a row's registry spec names. Empty when the row declares none, which keeps
     * the settings that predate the unit vocabulary on their own historical formatting.
     */
    private String unitSuffix(ControlSpec entry) {
        String unitKey = entry.slider == null ? null : entry.slider.unitKey;
        if (unitKey == null || unitKey.isEmpty()) return "";
        String resolved = host.strings().get(unitKey, "");
        if (!resolved.isEmpty()) return resolved;
        // Registry vocabulary fallbacks, same as EditorRows#unit uses for the editor sliders.
        if ("settings_unit_percent".equals(unitKey)) return "%";
        if ("settings_unit_dp".equals(unitKey)) return "dp";
        return "";
    }

    private static int snapToStep(int raw, int min, int max, int step) {
        int clamped = clamp(raw, min, max);
        if (step <= 1) return clamped;
        int snapped = min + Math.round((clamped - min) / (float) step) * step;
        return clamp(snapped, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public void textFieldRow(LinearLayout content, final Settings.StringSetting setting) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.setTag(PanelTags.row(setting));
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.START);
        row.addView(style.text(host.strings().setting(setting), 14, PanelStyle.COL_SUMMARY, false));
        EditText field = new EditText(style.context());
        field.setText(host.store().get(setting));
        if (setting == Settings.SPICY_ORG_CLIENT_KEY) {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        field.setTextColor(PanelStyle.COL_TITLE);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PanelStyle.COL_SECTION));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                host.writer().put(setting, s.toString());
            }
        });
        row.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void actionRow(LinearLayout content, Kind lead, String label, View.OnClickListener listener) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        if (lead != null) row.addView(style.kindView(lead, PanelStyle.COL_ACCENT, 19), style.leadParams());
        row.addView(style.text(label, 16, PanelStyle.COL_ACCENT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
    }

    public void infoRow(LinearLayout content, String label, String value) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        row.addView(style.text(label, 14, PanelStyle.COL_SUMMARY, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = style.text(value == null ? "" : value, 14, PanelStyle.COL_TITLE, false);
        val.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        row.addView(val, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** AI-provided row: label + accent value, optional icon actions and a chevron. */
    public void aiFieldRow(LinearLayout content, String label, String value, boolean selector,
                           String rowKey, View.OnClickListener listener,
                           AiSettingsRows.IconAction... actions) {
        PanelStyle style = host.style();
        LinearLayout row = style.newRow(content);
        if (rowKey != null) row.setTag(PanelTags.row(rowKey));
        TextView summary = style.titleColumn(row, label, value);
        summary.setTextColor(PanelStyle.COL_ACCENT);
        if (actions != null) {
            for (AiSettingsRows.IconAction action : actions) {
                if (action == null) continue;
                row.addView(style.iconButton(action));
            }
        }
        if (selector) {
            row.addView(style.kindView(Kind.CHEVRON_RIGHT, PanelStyle.COL_SECTION, 18),
                    new LinearLayout.LayoutParams(style.dp(28), style.dp(36)));
        }
        row.setOnClickListener(listener);
    }

    // --- In-place patching ---

    /** Patches a reused ordinary row: value, availability copy, and interactive state. */
    public void patchRow(View row, ControlSpec entry) {
        // Sliders are re-rendered fresh by the keyed sync (they own their thumb/track state), so a
        // patch would only fight the drawn position.
        if (entry.kind == ControlSpec.Kind.SLIDER) return;
        Settings.Setting<?> setting = entry.setting;
        boolean unavailable = host.unavailable(setting);
        ControlSpec.Kind kind = entry.kind;
        if (kind == ControlSpec.Kind.TEXT_FIELD && setting instanceof Settings.StringSetting) {
            EditText field = findEditText(row);
            if (field != null && !field.isFocused()) {
                String current = host.store().get((Settings.StringSetting) setting);
                if (!current.equals(field.getText().toString())) field.setText(current);
            }
        } else if (setting instanceof Settings.BooleanSetting
                && kind == ControlSpec.Kind.TOGGLE) {
            TextView sub = row.findViewWithTag(PanelTags.ROW_SUMMARY);
            if (sub instanceof TextView) {
                String text = switchSummary(setting, unavailable);
                sub.setText(text == null ? "" : text);
                sub.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
            }
            GlossyToggle toggle = findToggle(row);
            if (toggle != null) {
                toggle.setChecked(!unavailable && host.store().get((Settings.BooleanSetting) setting), false);
                toggle.setEnabled(!unavailable);
            }
            row.setEnabled(!unavailable);
            row.setAlpha(unavailable ? 0.48f : 1f);
        } else if (setting instanceof Settings.StringSetting) {
            TextView sub = row.findViewWithTag(PanelTags.ROW_SUMMARY);
            if (sub instanceof TextView) {
                Settings.StringSetting string = (Settings.StringSetting) setting;
                String summary = setting == Settings.CACHE_SIZE
                        ? host.cacheSizeSummary() : host.labelFor(string, host.store().get(string));
                sub.setText(unavailable ? host.unavailableSummary(setting) : summary);
                sub.setTextColor(unavailable ? PanelStyle.COL_SUMMARY : PanelStyle.COL_ACCENT);
            }
            row.setEnabled(!unavailable);
        } else if (setting instanceof Settings.IntegerSetting) {
            TextView value = row.findViewWithTag(PanelTags.ROW_VALUE);
            if (value instanceof TextView) {
                value.setText(SettingLabels.formatStepper(
                        (Settings.IntegerSetting) setting,
                        host.store().get((Settings.IntegerSetting) setting),
                        unitSuffix(entry)));
            }
        }
    }

    // --- Lookup helpers ---

    public static GlossyToggle findToggle(View row) {
        if (!(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof GlossyToggle) return (GlossyToggle) group.getChildAt(i);
        }
        return null;
    }

    public static EditText findEditText(View row) {
        if (!(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof EditText) return (EditText) group.getChildAt(i);
        }
        return null;
    }

    // --- Stepper mechanics ---

    /** Pill button holding a lucide minus/plus; touch logic is view-agnostic. */
    private View stepButton(boolean plus, Settings.IntegerSetting setting) {
        PanelStyle style = host.style();
        android.widget.ImageButton glyph = new android.widget.ImageButton(style.context());
        glyph.setImageDrawable(new ActionIconDrawable(plus ? Kind.PLUS : Kind.MINUS,
                PanelStyle.COL_TITLE, style.density()));
        int pad = style.dp(8);
        glyph.setPadding(pad, pad, pad, pad);
        String description = host.strings().format(
                plus ? "settings_stepper_increase" : "settings_stepper_decrease",
                plus ? "Increase %1$s" : "Decrease %1$s", host.strings().setting(setting));
        glyph.setContentDescription(description);
        glyph.setTooltipText(description);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x22FFFFFF);
        bg.setCornerRadius(style.dp(18));
        bg.setStroke(style.dp(1), PanelStyle.COL_CARD_BORDER);
        glyph.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return glyph;
    }

    /**
     * Steppers track the pending value locally and only commit ~250ms after the last tick:
     * expensive consumers would otherwise lag the press-and-hold ramp.
     */
    private void adjustStepper(Settings.IntegerSetting setting, String unit, TextView valueView,
                               int delta, int[] pending, Runnable commit) {
        int next = Math.max(setting.minValue, Math.min(setting.maxValue, pending[0] + delta));
        pending[0] = next;
        valueView.setText(SettingLabels.formatStepper(setting, next, unit));
        valueView.removeCallbacks(commit);
        valueView.postDelayed(commit, 250L);
    }

    private void attachStepperTouch(View button, final Settings.IntegerSetting setting,
                                    final String unit, final TextView valueView, final int delta,
                                    final int[] pending, final Runnable commit) {
        final int[] repeatCount = new int[]{0};
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            adjustStepper(setting, unit, valueView, delta, pending, commit);
            repeatCount[0]++;
            long delayMs = Math.max(45L, 130L - repeatCount[0] * 8L);
            button.postDelayed(repeat[0], delayMs);
        };
        button.setOnClickListener(v -> adjustStepper(setting, unit, valueView, delta, pending, commit));
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    // Without this the surrounding ScrollView intercepts on the slightest finger
                    // drift and cancels the hold, so press-and-hold repeat never ramps.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    repeatCount[0] = 0;
                    adjustStepper(setting, unit, valueView, delta, pending, commit);
                    v.removeCallbacks(repeat[0]);
                    v.postDelayed(repeat[0], 360L);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.sendAccessibilityEvent(
                            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                    // fall through
                case android.view.MotionEvent.ACTION_CANCEL:
                case android.view.MotionEvent.ACTION_OUTSIDE:
                    v.setPressed(false);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    v.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
    }
}
