package com.tosasitill.az100;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


/** Small diagnostic/settings page; the actual volume-panel hook stays in Melody. */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                + "\n\n下方按钮只调节降噪挡位，不再提供 EQ、自动暂停、语音提示或自适应功能。"
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
        setContentView(root);
    }

    private void sendCnc(int value) {
        try {
            MelodyProviderHook.configureBose(this, "cnc", value, 0, 0);
        } catch (Throwable error) {
            Logs.e("cnc button failed", error);
        }
    }

    private static void updateLevel(TextView view, int value) {
        view.setText("当前降噪挡位：" + value + " / 10");
    }
}
