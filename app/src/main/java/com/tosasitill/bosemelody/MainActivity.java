package com.tosasitill.bosemelody;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * Release settings page. It talks to the BMAP controller directly inside this
 * process: calling Melody's provider from here is impossible because the
 * module app does not hold com.oplus.permission.safe.IOT, which made every
 * button crash with a SecurityException.
 */
public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;

    /** Deep indigo -> accent blue, matches the launcher icon gradient. */
    private static final int GRAD_START = 0xFF1B2340;
    private static final int GRAD_END = 0xFF4F63E6;

    private TextView batteryValue;
    private ProgressBar batteryProgress;
    private TextView batteryHint;
    private TextView statusText;
    private TextView levelValue;
    private SeekBar cncSeek;
    private Switch ancSwitch;
    private RadioGroup spatialOptions;
    private Handler stateHandler;
    private Runnable statePoll;
    private boolean stateRefreshInFlight;
    private boolean applyingAudioState;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logs.traceToFile(getExternalFilesDir(null));
        boolean bluetoothReady = requestBluetoothPermissions();

        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int cardBg = night ? 0xFF181E30 : Color.WHITE;
        int textPrimary = night ? 0xFFEDEFF7 : 0xFF14171F;
        int textSecondary = night ? 0xFF9AA3BD : 0xFF5C6470;
        int accent = 0xFF4F63E6;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(buildHeader(night));
        root.addView(buildQuickModesCard(cardBg, textPrimary, textSecondary, accent));
        root.addView(buildBatteryCard(cardBg, textPrimary, textSecondary, accent));
        root.addView(buildAncCard(cardBg, textPrimary, textSecondary, accent));
        root.addView(buildAudioOptionsCard(cardBg, textPrimary, textSecondary, accent));
        root.addView(buildAboutCard(cardBg, textPrimary, textSecondary));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(night ? 0xFF0E1220 : 0xFFF4F5F7);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private boolean requestBluetoothPermissions() {
        // Both are runtime permissions on API 31+. cancelDiscovery() inside the
        // BMAP connect path needs BLUETOOTH_SCAN, so request it together with
        // BLUETOOTH_CONNECT or the settings page cannot read battery/state.
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
        }
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private View buildHeader(boolean night) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(dp(24), dp(40), dp(24), dp(32));
        header.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{GRAD_START, GRAD_END}));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_headphones);
        header.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView title = new TextView(this);
        title.setText("Bose Melody Control");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        header.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("QC Earbuds Ultra 2 · ColorOS 音量面板集成");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle, matchWrap());

        statusText = new TextView(this);
        statusText.setText("模块已激活 · " + BoseDeviceConfig.MAC);
        statusText.setTextSize(12f);
        statusText.setTextColor(0xB3FFFFFF);
        statusText.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x33FFFFFF);
        pill.setCornerRadius(dp(20));
        statusText.setBackground(pill);
        statusText.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = dp(16);
        header.addView(statusText, pillParams);
        return header;
    }

    private View buildQuickModesCard(int cardBg, int textPrimary, int textSecondary, int accent) {
        LinearLayout card = card(cardBg);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = cardTitle("快捷场景", textPrimary);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = new TextView(this);
        badge.setText("BMAP 直连");
        badge.setTextSize(11f);
        badge.setTextColor(accent);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(roundDrawable(0x164F63E6, dp(12)));
        heading.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(heading, matchWrap());
        TextView hint = new TextView(this);
        hint.setText("像 Bose Music 一样快速切换聆听场景");
        hint.setTextSize(12f);
        hint.setTextColor(textSecondary);
        hint.setPadding(0, 0, 0, dp(10));
        card.addView(hint, matchWrap());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"关闭", "降噪", "通透"};
        int[] modes = {BoseDeviceConfig.MODE_OFF, BoseDeviceConfig.MODE_QUIET, BoseDeviceConfig.MODE_AWARE};
        for (int i = 0; i < labels.length; i++) {
            final int mode = modes[i];
            TextView button = secondaryButton(labels[i], textPrimary,
                    view -> DirectBoseController.requestMode(getApplicationContext(), BoseDeviceConfig.MAC, mode));
            row.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1f));
            if (i < labels.length - 1) row.addView(new Space(this), new LinearLayout.LayoutParams(dp(8), 1));
        }
        card.addView(row, matchWrap());
        return card;
    }

    private View buildBatteryCard(int cardBg, int textPrimary, int textSecondary, int accent) {
        LinearLayout card = card(cardBg);

        card.addView(cardTitle("耳机电量", textPrimary));
        batteryHint = new TextView(this);
        batteryHint.setText("点击下方按钮通过 BMAP 读取左 / 右耳与充电盒电量");
        batteryHint.setTextSize(13f);
        batteryHint.setTextColor(textSecondary);
        batteryHint.setPadding(0, 0, 0, dp(14));
        card.addView(batteryHint, matchWrap());

        batteryValue = new TextView(this);
        batteryValue.setText("— %");
        batteryValue.setTextSize(20f);
        batteryValue.setTypeface(Typeface.DEFAULT_BOLD);
        batteryValue.setTextColor(textPrimary);
        batteryValue.setGravity(Gravity.CENTER);
        card.addView(batteryValue, matchWrap());

        batteryProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        batteryProgress.setMax(100);
        batteryProgress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        progressParams.topMargin = dp(12);
        card.addView(batteryProgress, progressParams);

        card.addView(primaryButton("读取电量", accent, view -> readBattery()));
        return card;
    }

    private View buildAncCard(int cardBg, int textPrimary, int textSecondary, int accent) {
        LinearLayout card = card(cardBg);

        card.addView(cardTitle("降噪强度", textPrimary));
        TextView ancHint = new TextView(this);
        ancHint.setText("0 = 最强降噪 · 10 = 环境声最多；需处于降噪模式时效果最明显");
        ancHint.setTextSize(13f);
        ancHint.setTextColor(textSecondary);
        ancHint.setPadding(0, 0, 0, dp(8));
        card.addView(ancHint, matchWrap());

        byte[] cached = DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC);
        final int initialLevel = cached != null && cached.length >= 1
                ? Math.max(0, Math.min(10, cached[0] & 0xff)) : 5;
        levelValue = new TextView(this);
        levelValue.setText(initialLevel + " / 10");
        levelValue.setTextSize(28f);
        levelValue.setTypeface(Typeface.DEFAULT_BOLD);
        levelValue.setTextColor(accent);
        levelValue.setGravity(Gravity.CENTER);
        card.addView(levelValue, matchWrap());

        final int[] level = {5};
        SeekBar seek = new SeekBar(this);
        cncSeek = seek;
        seek.setMax(10);
        seek.setProgress(initialLevel);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seek.getThumb().setTint(accent);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                levelValue.setText(value + " / 10");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                level[0] = bar.getProgress();
                sendCnc(level[0]);
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekParams.topMargin = dp(6);
        card.addView(seek, seekParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(14);

        View down = secondaryButton("− 降一档", textPrimary, view -> {
            level[0] = Math.max(0, level[0] - 1);
            seek.setProgress(level[0]);
            sendCnc(level[0]);
        });
        View up = secondaryButton("+ 升一档", textPrimary, view -> {
            level[0] = Math.min(10, level[0] + 1);
            seek.setProgress(level[0]);
            sendCnc(level[0]);
        });
        row.addView(down, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        upParams.leftMargin = dp(12);
        row.addView(up, upParams);
        card.addView(row, rowParams);
        return card;
    }

    private View buildAudioOptionsCard(int cardBg, int textPrimary,
                                       int textSecondary, int accent) {
        LinearLayout card = card(cardBg);
        card.addView(cardTitle("音频选项", textPrimary));
        TextView hint = new TextView(this);
        hint.setText("设置立即写入耳机；从降噪挡位调整、读取电量时同步状态");
        hint.setTextSize(13f);
        hint.setTextColor(textSecondary);
        hint.setPadding(0, 0, 0, dp(10));
        card.addView(hint, matchWrap());

        ancSwitch = addAudioSwitch(card, "主动降噪（ANC）", "关闭后为安静模式，不进行降噪", textPrimary, accent,
                DirectBoseController.AUDIO_ANC);
        addSpatialOptions(card, textPrimary, textSecondary, accent);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.topMargin = dp(8);
        card.addView(secondaryButton("同步耳机设置", textPrimary, view -> refreshAudioState()), refreshParams);
        applyCachedAudioState();
        if (DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC) == null) {
            refreshAudioState();
        }
        return card;
    }

    private Switch addAudioSwitch(LinearLayout card, String title, String subtitle,
                                  int textPrimary, int accent, int field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(textPrimary);
        labels.addView(titleView, matchWrap());
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12f);
        subtitleView.setTextColor(textPrimary == Color.WHITE ? 0xFF9AA3BD : 0xFF737B88);
        labels.addView(subtitleView, matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (applyingAudioState) return;
            DirectBoseController.setAudioOption(getApplicationContext(), field, checked ? 1 : 0);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, matchWrap());
        return toggle;
    }

    private void addSpatialOptions(LinearLayout card, int textPrimary,
                                   int textSecondary, int accent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(8), 0, dp(8));
        TextView title = new TextView(this);
        title.setText("空间音频");
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textPrimary);
        group.addView(title, matchWrap());
        TextView hint = new TextView(this);
        hint.setText("选择空间效果；常规听歌可选关闭");
        hint.setTextSize(12f);
        hint.setTextColor(textSecondary);
        group.addView(hint, matchWrap());
        spatialOptions = new RadioGroup(this);
        spatialOptions.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"关闭", "Room", "Head"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(100 + i);
            option.setText(labels[i]);
            option.setTextSize(13f);
            option.setTextColor(textPrimary);
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            spatialOptions.addView(option, new RadioGroup.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        spatialOptions.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            if (applyingAudioState || checkedId < 100 || checkedId > 102) return;
            DirectBoseController.setAudioOption(getApplicationContext(),
                    DirectBoseController.AUDIO_SPATIAL, checkedId - 100);
        });
        group.addView(spatialOptions, matchWrap());
        card.addView(group, matchWrap());
    }

    private void applyCachedAudioState() {
        byte[] settings = DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC);
        if (settings == null || settings.length < 5) return;
        applyingAudioState = true;
        try {
            if (ancSwitch != null) ancSwitch.setChecked(settings[4] != 0);
            if (spatialOptions != null) {
                int spatial = Math.max(0, Math.min(2, settings[2] & 0xff));
                spatialOptions.check(100 + spatial);
            }
        } finally {
            applyingAudioState = false;
        }
        if (levelValue != null) levelValue.setText((settings[0] & 0xff) + " / 10");
        if (cncSeek != null) cncSeek.setProgress(Math.max(0, Math.min(10, settings[0] & 0xff)));
    }

    private void refreshAudioState() {
        if (stateRefreshInFlight) return;
        stateRefreshInFlight = true;
        if (stateHandler != null && statePoll != null) stateHandler.removeCallbacks(statePoll);
        DirectBoseController.refreshState(getApplicationContext(), BoseDeviceConfig.MAC);
        stateHandler = new Handler(Looper.getMainLooper());
        final long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
        statePoll = new Runnable() {
            @Override public void run() {
                byte[] settings = DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC);
                if (settings != null && settings.length >= 5) {
                    applyCachedAudioState();
                    if (batteryHint != null) batteryHint.setText("耳机设置已同步");
                    stateRefreshInFlight = false;
                    return;
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    if (batteryHint != null) batteryHint.setText("同步超时：请确认耳机已连接后重试");
                    stateRefreshInFlight = false;
                    return;
                }
                stateHandler.postDelayed(this, 400L);
            }
        };
        stateHandler.postDelayed(statePoll, 400L);
    }

    private View buildAboutCard(int cardBg, int textPrimary, int textSecondary) {
        LinearLayout card = card(cardBg);
        card.addView(cardTitle("使用说明", textPrimary));
        TextView about = new TextView(this);
        about.setText("1. 在 LSPosed 中启用本模块，作用域仅勾选 com.oplus.melody，然后重启 Melody。\n"
                + "2. 耳机连接后，下拉音量面板即可在「关闭 / 降噪 / 通透」之间切换。\n"
                + "3. 首次使用请允许「附近的设备」权限，否则本页无法读取耳机设置或电量。\n"
                + "4. 本页与面板共用同一条 BMAP 短连接链路，读取时请避免同时切换模式。\n\n"
                + "版本 " + BuildConfig.VERSION_NAME);
        about.setTextSize(13f);
        about.setTextColor(textSecondary);
        about.setLineSpacing(dp(4), 1f);
        card.addView(about, matchWrap());
        return card;
    }

    private void readBattery() {
        batteryHint.setText("正在连接耳机读取…");
        batteryValue.setText("…");
        try {
            DirectBoseController.refreshState(getApplicationContext(), BoseDeviceConfig.MAC);
            Handler handler = new Handler(Looper.getMainLooper());
            final long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
            Runnable poll = new Runnable() {
                @Override public void run() {
                    int[] battery = DirectBoseController.cachedBattery(BoseDeviceConfig.MAC);
                                byte[] audio = DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC);
                    if (audio != null && audio.length >= 5) applyCachedAudioState();
                    if (battery != null && (battery[0] >= 0 || battery[1] >= 0)) {
                        showBattery(battery);
                        return;
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        batteryHint.setText("读取超时：请确认耳机已连接，或稍后重试");
                        batteryValue.setText("— %");
                        return;
                    }
                    handler.postDelayed(this, 400L);
                }
            };
            handler.postDelayed(poll, 400L);
        } catch (Throwable error) {
            batteryHint.setText("读取失败：" + error.getClass().getSimpleName());
            batteryValue.setText("— %");
            Logs.e("sync button failed", error);
        }
    }

    private void showBattery(int[] battery) {
        String left = battery[0] >= 0 ? battery[0] + "%" : "–";
        String right = battery[1] >= 0 ? battery[1] + "%" : "–";
        String box = battery[2] >= 0 ? battery[2] + "%" : "–";
        batteryValue.setText("左 " + left + "   右 " + right + "   盒 " + box);
        batteryHint.setText("已通过 BMAP 读取 · " + new java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
        int show = battery[3] >= 0 ? battery[3]
                : Math.max(battery[0], battery[1]);
        batteryProgress.setProgress(Math.max(0, Math.min(100, show)));
    }

    @Override protected void onDestroy() {
        if (stateHandler != null && statePoll != null) stateHandler.removeCallbacks(statePoll);
        super.onDestroy();
    }

    private void sendCnc(int value) {
        try {
            DirectBoseController.setCnc(getApplicationContext(), value);
        } catch (Throwable error) {
            Logs.e("cnc button failed", error);
        }
    }

    // ---------- small view helpers ----------

    private GradientDrawable roundDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout card(int background) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(20));
        layout.setBackground(drawable);
        layout.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(16);
        params.rightMargin = dp(16);
        params.topMargin = dp(14);
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView cardTitle(String text, int color) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color);
        title.setPadding(0, 0, 0, dp(8));
        return title;
    }

    private View primaryButton(String text, int accent, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(15f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable drawable = roundDrawable(accent, dp(14));
        button.setBackground(drawable);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        button.setLayoutParams(params);
        return button;
    }

    private TextView secondaryButton(String text, int textColor, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(14f);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable drawable = roundDrawable(0x144F63E6, dp(12));
        button.setBackground(drawable);
        button.setOnClickListener(listener);
        return button;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
