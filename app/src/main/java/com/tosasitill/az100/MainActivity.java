package com.tosasitill.az100;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Diagnostic/settings page. It talks to the BMAP controller directly inside
 * this process: calling Melody's provider from here is impossible because the
 * module app does not hold com.oplus.permission.safe.IOT, which made every
 * button crash with a SecurityException.
 */
public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Bose QC Earbuds Ultra 2\nColorOS Melody 控制");
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView details = new TextView(this);
        details.setText("地址：" + BoseDeviceConfig.MAC
                + "\n原生模式：关闭 / 降噪 / 通透"
                + "\n\n下方按钮直接通过 BMAP 短连接调节降噪挡位，不再经过 Melody Provider。"
                + "\n\n请在 LSPosed 中仅勾选 com.oplus.melody，然后重启 Melody。"
                + "\n调试日志：adb logcat -s MelodyEarphone:V");
        details.setTextSize(15f);
        details.setPadding(0, 40, 0, 0);
        root.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView levelText = new TextView(this);
        levelText.setTextSize(17f);
        levelText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(levelText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] level = {5};
        updateLevel(levelText, level[0]);

        Button down = new Button(this);
        down.setText("降噪挡位 -");
        down.setOnClickListener(view -> {
            level[0] = Math.max(0, level[0] - 1);
            updateLevel(levelText, level[0]);
            sendCnc(level[0]);
        });
        root.addView(down);

        Button up = new Button(this);
        up.setText("降噪挡位 +");
        up.setOnClickListener(view -> {
            level[0] = Math.min(10, level[0] + 1);
            updateLevel(levelText, level[0]);
            sendCnc(level[0]);
        });
        root.addView(up);

        Button sync = new Button(this);
        sync.setText("同步耳机状态并读取电量");
        sync.setOnClickListener(view -> {
            sync.setEnabled(false);
            levelText.setText("正在连接耳机读取…");
            try {
                DirectBoseController.refreshState(getApplicationContext(), BoseDeviceConfig.MAC);
                Handler handler = new Handler(Looper.getMainLooper());
                final long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
                Runnable poll = new Runnable() {
                    @Override public void run() {
                        int[] battery = DirectBoseController.cachedBattery(BoseDeviceConfig.MAC);
                        if (battery != null && (battery[0] >= 0 || battery[1] >= 0)) {
                            levelText.setText("左 " + battery[0] + "%  右 " + battery[1]
                                    + "%  盒 " + battery[2] + "%");
                            sync.setEnabled(true);
                            return;
                        }
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                            levelText.setText("读取超时或耳机未响应，请查看 bose-melody.log");
                            sync.setEnabled(true);
                            return;
                        }
                        handler.postDelayed(this, 400L);
                    }
                };
                handler.postDelayed(poll, 400L);
            } catch (Throwable error) {
                sync.setEnabled(true);
                levelText.setText("读取失败：" + error.getClass().getSimpleName());
                Logs.e("sync button failed", error);
            }
        });
        root.addView(sync);
        setContentView(root);
    }

    private void sendCnc(int value) {
        try {
            DirectBoseController.setCnc(getApplicationContext(), value);
        } catch (Throwable error) {
            Logs.e("cnc button failed", error);
        }
    }

    private static void updateLevel(TextView view, int value) {
        view.setText("当前降噪挡位：" + value + " / 10");
    }
}
