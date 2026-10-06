package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * V3.5: 未投放应用时常亮
 * 背屏的自动熄屏时间由系统设置 subscreen_display_time 控制（默认10000ms）。
 * 开启时把它设为最大值，背屏不再超时熄屏；关闭时恢复原值。
 * 不再循环发送KEYCODE_WAKEUP：那样无法阻止超时熄屏（只会熄屏后立刻重新点亮），
 * 还会撤销用户的双击熄屏。现在双击背屏熄屏/亮屏由系统原生处理。
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final String TIMEOUT_SETTING = "subscreen_display_time";
    private static final String MAX_TIMEOUT = String.valueOf(Integer.MAX_VALUE);
    private static final String DEFAULT_TIMEOUT = "10000";
    private static final String PREF_SAVED_TIMEOUT = "always_wakeup_saved_subscreen_timeout";

    private ITaskService taskService;
    private SharedPreferences prefs;
    private boolean bound = false;

    private final Shizuku.UserServiceArgs serviceArgs =
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("always_wakeup_task_service")
            .debuggable(false)
            .version(1);

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            applySetting();
            stopSelf();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (taskService != null) {
            applySetting();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!bound) {
                Shizuku.bindUserService(serviceArgs, taskServiceConnection);
                bound = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "绑定TaskService失败", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * 根据开关状态设置或恢复背屏熄屏时间
     */
    private void applySetting() {
        boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
        try {
            String current = taskService.executeShellCommandWithResult(
                "settings get system " + TIMEOUT_SETTING);
            current = current == null ? "" : current.trim();

            if (enabled) {
                // 保存原值（已是最大值说明之前已开启过，不覆盖保存的原值）
                if (!current.isEmpty() && !current.equals("null") && !current.equals(MAX_TIMEOUT)) {
                    prefs.edit().putString(PREF_SAVED_TIMEOUT, current).apply();
                }
                taskService.executeShellCommand("settings put system " + TIMEOUT_SETTING + " " + MAX_TIMEOUT);
                Log.d(TAG, "✓ 背屏熄屏时间已设为最大值（原值 " + current + "）");
            } else {
                String saved = prefs.getString(PREF_SAVED_TIMEOUT, DEFAULT_TIMEOUT);
                // 只有在仍是我们设置的最大值时才恢复，避免覆盖用户之后手动修改的值
                if (current.equals(MAX_TIMEOUT)) {
                    taskService.executeShellCommand("settings put system " + TIMEOUT_SETTING + " " + saved);
                    Log.d(TAG, "✓ 背屏熄屏时间已恢复为 " + saved);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "设置背屏熄屏时间失败", t);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (bound) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to unbind TaskService: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
