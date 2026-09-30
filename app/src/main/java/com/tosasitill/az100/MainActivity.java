package com.tosasitill.az100;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
                + "\n模式：关闭 / 降噪 / 通透 / 沉浸式音频"
                + "\n\n支持：左右耳与充电盒电量、当前模式同步、EQ、自动暂停、语音提示。"
                + "\n佩戴检测：当前 BMAP 固件仅可靠提供自动暂停开关，未暴露实时传感器状态。"
                + "\n\n请在 LSPosed 中仅勾选 com.oplus.melody，然后重启 Melody。"
                + "\n调试日志：adb logcat -s MelodyEarphone:V");
        details.setTextSize(15f);
        details.setPadding(0, 40, 0, 0);
        root.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText eq = new EditText(this);
        eq.setHint("EQ：低音,中音,高音（-10 到 10），例如 0,0,0");
        root.addView(eq);
        Button saveEq = new Button(this);
        saveEq.setText("应用 EQ");
        saveEq.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String[] values = eq.getText().toString().trim().split(",");
                if (values.length != 3) return;
                try {
                    int bass = Integer.parseInt(values[0].trim());
                    int mid = Integer.parseInt(values[1].trim());
                    int treble = Integer.parseInt(values[2].trim());
                    MelodyProviderHook.configureBose(MainActivity.this, "eq", bass, mid, treble);
                } catch (NumberFormatException ignored) {
                }
            }
        });
        root.addView(saveEq);

        Button autoPause = new Button(this);
        autoPause.setText("切换摘戴自动暂停");
        autoPause.setOnClickListener(new View.OnClickListener() {
            private boolean enabled = true;
            @Override public void onClick(View view) {
                enabled = !enabled;
                MelodyProviderHook.configureBose(MainActivity.this, "auto_pause", enabled ? 1 : 0, 0, 0);
                autoPause.setText("摘戴自动暂停：" + (enabled ? "开启" : "关闭"));
            }
        });
        root.addView(autoPause);

        Button prompts = new Button(this);
        prompts.setText("切换语音提示");
        prompts.setOnClickListener(new View.OnClickListener() {
            private boolean enabled = true;
            @Override public void onClick(View view) {
                enabled = !enabled;
                MelodyProviderHook.configureBose(MainActivity.this, "prompts", enabled ? 1 : 0, 0, 0);
                prompts.setText("语音提示：" + (enabled ? "开启" : "关闭"));
            }
        });
        root.addView(prompts);

        Button cncDown = new Button(this);
        cncDown.setText("降噪强度：减少一级");
        cncDown.setOnClickListener(new View.OnClickListener() {
            private int level = 5;
            @Override public void onClick(View view) {
                level = Math.max(0, level - 1);
                MelodyProviderHook.configureBose(MainActivity.this, "cnc", level, 0, 0);
                cncDown.setText("降噪强度：" + level + " / 10");
            }
        });
        root.addView(cncDown);

        Button cncUp = new Button(this);
        cncUp.setText("降噪强度：增加一级");
        cncUp.setOnClickListener(new View.OnClickListener() {
            private int level = 5;
            @Override public void onClick(View view) {
                level = Math.min(10, level + 1);
                MelodyProviderHook.configureBose(MainActivity.this, "cnc", level, 0, 0);
                cncUp.setText("降噪强度：" + level + " / 10");
            }
        });
        root.addView(cncUp);
        setContentView(root);
    }
}
