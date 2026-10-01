package com.tosasitill.bosemelody;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Bose controls injected into Melody's native device-detail page.
 * This view lives in the Melody process and talks to the existing BMAP session
 * directly; it does not launch the separate Bose control application.
 */
final class BoseDetailPanel extends LinearLayout {
    private static final int ACCENT = 0xFF4F63E6;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView connection;
    private final TextView battery;
    private final TextView batteryHint;
    private final TextView cncValue;
    private final SeekBar cncSeek;
    private final Switch ancSwitch;
    private final RadioGroup spatialGroup;
    private boolean applying;
    private boolean refreshPending;

    private BoseDetailPanel(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(18));
        setBackground(round(0xFFF7F8FC, dp(18)));
        setElevation(dp(2));

        TextView title = text("Bose QC Earbuds Ultra 2", 18, Color.rgb(20, 23, 31), true);
        addView(title, wrap());
        TextView subtitle = text("Bose 控制 · 直接集成到 Melody", 12, 0xFF687080, false);
        subtitle.setPadding(0, dp(3), 0, dp(8));
        addView(subtitle, wrap());

        connection = text("正在检查耳机连接…", 12, 0xFF687080, false);
        addView(connection, wrap());

        addView(section("耳机电量"), wrap());
        battery = text("左 —    右 —    盒 —", 16, 0xFF14171F, true);
        battery.setGravity(Gravity.CENTER);
        addView(battery, wrap());
        batteryHint = text("点击刷新读取 BMAP 电量", 12, 0xFF687080, false);
        batteryHint.setGravity(Gravity.CENTER);
        addView(batteryHint, wrap());
        ProgressBar progress = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        addView(progress, params(-1, dp(5), 0, dp(8), 0, 0));
        addView(button("刷新耳机状态", true, v -> refresh()), wrap());

        addView(section("降噪模式"), params(-1, -2, 0, dp(12), 0, 0));
        addView(modeRow(), wrap());

        addView(section("CNC 降噪等级"), params(-1, -2, 0, dp(12), 0, 0));
        cncValue = text("5 / 10", 20, ACCENT, true);
        cncValue.setGravity(Gravity.CENTER);
        addView(cncValue, wrap());
        cncSeek = new SeekBar(context);
        cncSeek.setMax(10);
        cncSeek.setProgress(5);
        cncSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        cncSeek.getThumb().setTint(ACCENT);
        cncSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                cncValue.setText(value + " / 10");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                DirectBoseController.setCnc(getContext(), bar.getProgress());
            }
        });
        addView(cncSeek, wrap());

        addView(section("音频选项"), params(-1, -2, 0, dp(12), 0, 0));
        ancSwitch = addSwitch("主动降噪（ANC）", "关闭后为安静模式",
                DirectBoseController.AUDIO_ANC);
        TextView spatialTitle = text("空间音频", 14, 0xFF14171F, true);
        spatialTitle.setPadding(0, dp(8), 0, 0);
        addView(spatialTitle, wrap());
        spatialGroup = new RadioGroup(context);
        spatialGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"关闭", "Room", "Head"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton item = new RadioButton(context);
            item.setId(200 + i);
            item.setText(labels[i]);
            item.setTextSize(12);
            item.setTextColor(0xFF14171F);
            item.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            spatialGroup.addView(item, new RadioGroup.LayoutParams(0, -2, 1));
        }
        spatialGroup.setOnCheckedChangeListener((group, id) -> {
            if (!applying && id >= 200 && id <= 202) {
                DirectBoseController.setAudioOption(getContext(),
                        DirectBoseController.AUDIO_SPATIAL, id - 200);
            }
        });
        addView(spatialGroup, wrap());
        refresh();
    }

    static void install(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            int id = activity.getResources().getIdentifier(
                    "melody_ui_detail_container", "id", activity.getPackageName());
            View container = id == 0 ? null : activity.findViewById(id);
            if (!(container instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) container;
            if (parent.findViewWithTag("bose_detail_panel") != null) return;
            BoseDetailPanel panel = new BoseDetailPanel(activity);
            panel.setTag("bose_detail_panel");
            parent.addView(panel, new ViewGroup.LayoutParams(-1, -2));
            Logs.trace("bose native detail panel injected");
        } catch (Throwable error) {
            Logs.e("bose detail panel injection failed", error);
        }
    }

    private View modeRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        String[] labels = {"关闭", "降噪", "通透"};
        int[] modes = {1, 5, 2};
        for (int i = 0; i < labels.length; i++) {
            final int mode = modes[i];
            TextView item = button(labels[i], false,
                    v -> MelodyProviderHook.requestBoseMode(getContext(), mode));
            row.addView(item, new LayoutParams(0, -2, 1));
            if (i != labels.length - 1) {
                LayoutParams spacer = new LayoutParams(dp(8), 1);
                row.addView(new View(getContext()), spacer);
            }
        }
        return row;
    }

    private Switch addSwitch(String title, String subtitle, int field) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(VERTICAL);
        labels.addView(text(title, 14, 0xFF14171F, true), wrap());
        labels.addView(text(subtitle, 11, 0xFF687080, false), wrap());
        row.addView(labels, new LayoutParams(0, -2, 1));
        Switch toggle = new Switch(getContext());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!applying) DirectBoseController.setAudioOption(getContext(), field, checked ? 1 : 0);
        });
        row.addView(toggle, wrap());
        addView(row, wrap());
        return toggle;
    }

    private void refresh() {
        if (refreshPending) return;
        refreshPending = true;
        connection.setText("正在同步 Bose 耳机状态…");
        DirectBoseController.refreshState(getContext(), BoseDeviceConfig.MAC);
        final long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
        handler.post(new Runnable() {
            @Override public void run() {
                int[] values = DirectBoseController.cachedBattery(BoseDeviceConfig.MAC);
                byte[] audio = DirectBoseController.cachedAudioSettings(BoseDeviceConfig.MAC);
                if (values != null) {
                    battery.setText("左 " + value(values, 0) + "    右 " + value(values, 1)
                            + "    盒 " + value(values, 2));
                    batteryHint.setText("BMAP 电量已同步");
                }
                if (audio != null && audio.length >= 5) applyAudio(audio);
                if ((values != null && (values[0] >= 0 || values[1] >= 0)) || audio != null
                        || android.os.SystemClock.elapsedRealtime() >= deadline) {
                    connection.setText("Bose 已连接 · " + BoseDeviceConfig.MAC);
                    refreshPending = false;
                } else {
                    handler.postDelayed(this, 400L);
                }
            }
        });
    }

    private void applyAudio(byte[] values) {
        applying = true;
        try {
            cncSeek.setProgress(Math.max(0, Math.min(10, values[0] & 0xff)));
            ancSwitch.setChecked(values[4] != 0);
            spatialGroup.check(200 + Math.max(0, Math.min(2, values[2] & 0xff)));
        } finally {
            applying = false;
        }
    }

    private static String value(int[] values, int index) {
        return index < values.length && values[index] >= 0 ? values[index] + "%" : "—";
    }

    private TextView section(String value) {
        TextView view = text(value, 15, 0xFF14171F, true);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private TextView button(String value, boolean primary, OnClickListener listener) {
        TextView view = text(value, 13, primary ? Color.WHITE : 0xFF14171F, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setBackground(round(primary ? ACCENT : 0xFFE8EAF2, dp(12)));
        view.setOnClickListener(listener);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LayoutParams wrap() {
        return new LayoutParams(-1, -2);
    }

    private LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LayoutParams p = new LayoutParams(width, height);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    private android.graphics.drawable.Drawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
