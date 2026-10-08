package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * 快捷设置磁贴：开关"未投放应用时常亮"（Always Wake Up）
 * 与应用内开关使用同样的存储：原生 mrss_settings 和 Flutter 的 FlutterSharedPreferences，
 * 开启时同样关闭互斥的"背屏常亮"
 */
public class AlwaysWakeUpTileService extends TileService {
    private static final String TAG = "AlwaysWakeUpTile";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(isEnabled());
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean enabled = !isEnabled();

        getSharedPreferences("mrss_settings", MODE_PRIVATE).edit()
            .putBoolean("always_wakeup_enabled", enabled).apply();
        SharedPreferences flutterPrefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
        SharedPreferences.Editor editor = flutterPrefs.edit().putBoolean("flutter.always_wakeup_enabled", enabled);

        // 开启时关闭互斥的"背屏常亮"（与应用内开关逻辑一致）
        boolean disableKeepScreenOn = enabled && flutterPrefs.getBoolean("flutter.keep_screen_on_enabled", true);
        if (disableKeepScreenOn) {
            editor.putBoolean("flutter.keep_screen_on_enabled", false);
        }
        editor.apply();

        try {
            if (disableKeepScreenOn) {
                Intent keepIntent = new Intent(this, RearScreenKeeperService.class);
                keepIntent.setAction("ACTION_SET_KEEP_SCREEN_ON_ENABLED");
                keepIntent.putExtra("enabled", false);
                startService(keepIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "通知RearScreenKeeperService失败: " + e.getMessage());
        }

        // 服务根据开关状态设置/恢复背屏熄屏时间；关闭时恢复后自行退出
        try {
            Intent intent = new Intent(this, AlwaysWakeUpService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动AlwaysWakeUpService失败", e);
        }

        updateTile(enabled);
    }

    private boolean isEnabled() {
        return getSharedPreferences("mrss_settings", MODE_PRIVATE).getBoolean("always_wakeup_enabled", false);
    }

    private void updateTile(boolean enabled) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
