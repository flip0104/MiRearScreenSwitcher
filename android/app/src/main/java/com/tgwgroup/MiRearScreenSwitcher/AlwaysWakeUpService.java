package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * V3.5: 未投放应用时常亮
 * 背屏的自动熄屏时间由系统设置 subscreen_display_time 控制（默认10000ms）。
 * 开启时把它设为最大值，背屏不再超时熄屏；关闭时恢复原值。
 * 不循环发送KEYCODE_WAKEUP：那样无法阻止超时熄屏（只会熄屏后立刻重新点亮），
 * 还会撤销用户的双击熄屏。双击背屏熄屏/亮屏由系统原生处理。
 *
 * 另外：
 * - 省电模式下恢复原熄屏时间，退出省电模式后再设为最大值
 * - 两块屏都亮时按电源键，系统会同时熄灭两块屏，需要重新点亮背屏。
 *   系统熄灭背屏时会打印日志 "Powering off display group due to power_button (groupId= 1, ..."，
 *   背屏本已休眠时不会打印。通过Shizuku监听这条日志，只在背屏确实被电源键熄灭时重新点亮。
 *   （背屏的Display状态要几秒后才更新，不能用来判断背屏之前是否亮着）
 * - 背屏打开小米小部件面板（SmartAssistant）时恢复原熄屏时间，回到壁纸后再设为最大值。
 *   小米背屏Launcher会打印 "onSmartAssistantStateChanged: activated=true/false"。
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    private static final String TIMEOUT_SETTING = "subscreen_display_time";
    private static final String MAX_TIMEOUT = String.valueOf(Integer.MAX_VALUE);
    private static final String DEFAULT_TIMEOUT = "10000";
    private static final String PREF_SAVED_TIMEOUT = "always_wakeup_saved_subscreen_timeout";
    private static final String MIUI_POWER_SAVE_CHANGED = "miui.intent.action.POWER_SAVE_MODE_CHANGED";
    private static final int REAR_DISPLAY_ID = 1;
    private static final String POWER_GROUP_LOGCAT_ARGS = "-b system -s PowerGroup:I"; // system_server的日志在system缓冲区
    // 背屏(display group 1)被电源键熄灭的日志
    private static final String REAR_POWER_KEY_OFF_REGEX = "due to power_button \\(groupId= ?1,";
    // 小米背屏Launcher的小部件面板开关日志（应用日志在main缓冲区）
    private static final String WIDGET_PANEL_LOGCAT_ARGS = "-b main -s SubScreenCenter_SmartAssistantManager:D";
    private static final String WIDGET_PANEL_REGEX = "onSmartAssistantStateChanged: activated=(true|false)";

    private ITaskService taskService;
    private SharedPreferences prefs;
    private PowerManager powerManager;
    private Handler mainHandler;
    private boolean bound = false;
    private volatile boolean destroyed = false;
    // 省电模式状态缓存（读取需要shell命令，熄屏路径上不能等）
    private volatile boolean powerSaveMode = false;
    private Thread powerKeyThread;
    private Thread widgetPanelThread;
    // 背屏是否正在显示小米小部件面板
    private volatile boolean widgetPanelOpen = false;

    private final Shizuku.UserServiceArgs serviceArgs =
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("always_wakeup_task_service")
            .debuggable(false)
            .version(2); // V2: 新增 waitForLogLine

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            Log.d(TAG, "✓ TaskService connected");
            applyTimeout();
            startPowerKeyWatcher();
            startWidgetPanelWatcher();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠️ TaskService disconnected");
            taskService = null;
            bound = false;
            if (!destroyed) {
                mainHandler.postDelayed(AlwaysWakeUpService.this::bindTaskService, 1000);
            }
        }
    };

    private final BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // MIUI的广播可能早于系统省电状态更新，稍后再检查几次
            applyTimeout();
            mainHandler.postDelayed(AlwaysWakeUpService.this::applyTimeout, 1000);
            mainHandler.postDelayed(AlwaysWakeUpService.this::applyTimeout, 3000);
        }
    };

    /**
     * 后台线程循环等待"背屏被电源键熄灭"的系统日志，收到后立即重新点亮背屏
     */
    private void startPowerKeyWatcher() {
        if (powerKeyThread != null) return;
        powerKeyThread = new Thread(() -> {
            while (!destroyed) {
                ITaskService ts = taskService;
                if (ts == null) {
                    SystemClock.sleep(1000);
                    continue;
                }
                try {
                    String line = ts.waitForLogLine(POWER_GROUP_LOGCAT_ARGS, REAR_POWER_KEY_OFF_REGEX);
                    if (line == null) {
                        SystemClock.sleep(1000);
                        continue;
                    }
                    // 小部件面板打开时也要重新点亮：背屏原本亮着，之后按正常熄屏时间熄灭
                    if (!destroyed && !powerSaveMode) {
                        Log.d(TAG, "🔌 电源键熄灭了背屏，重新点亮");
                        wakeRearScreen();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "电源键监听失败: " + t.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        }, "AlwaysWakeUpPowerKey");
        powerKeyThread.setDaemon(true);
        powerKeyThread.start();
    }

    /**
     * 后台线程循环等待小部件面板的开关日志，打开时恢复原熄屏时间，关闭时设为最大值
     */
    private void startWidgetPanelWatcher() {
        if (widgetPanelThread != null) return;
        widgetPanelThread = new Thread(() -> {
            while (!destroyed) {
                ITaskService ts = taskService;
                if (ts == null) {
                    SystemClock.sleep(1000);
                    continue;
                }
                try {
                    String line = ts.waitForLogLine(WIDGET_PANEL_LOGCAT_ARGS, WIDGET_PANEL_REGEX);
                    if (line == null) {
                        SystemClock.sleep(1000);
                        continue;
                    }
                    boolean open = line.contains("activated=true");
                    if (open != widgetPanelOpen && !destroyed) {
                        widgetPanelOpen = open;
                        Log.d(TAG, open ? "🧩 小部件面板打开，恢复背屏熄屏时间" : "🖼 回到壁纸，背屏常亮");
                        mainHandler.post(this::applyTimeout);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "小部件面板监听失败: " + t.getMessage());
                    SystemClock.sleep(1000);
                }
            }
        }, "AlwaysWakeUpWidgetPanel");
        widgetPanelThread.setDaemon(true);
        widgetPanelThread.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        createForegroundNotification();

        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(MIUI_POWER_SAVE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(powerSaveReceiver, filter);
        }

        bindTaskService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean("always_wakeup_enabled", false)) {
            // 开关已关闭：恢复熄屏时间后退出
            if (taskService != null) {
                applyTimeout();
                stopSelf();
            }
            // 否则等TaskService连接后在applyTimeout中恢复并退出
            return START_NOT_STICKY;
        }
        if (taskService != null) applyTimeout();
        return START_STICKY;
    }

    private void bindTaskService() {
        try {
            if (taskService != null || bound) return;
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            bound = true;
        } catch (Exception e) {
            Log.e(TAG, "绑定TaskService失败", e);
        }
    }

    /**
     * MIUI省电模式不一定同步到 PowerManager.isPowerSaveMode()，同时检查MIUI和系统的设置项
     */
    private boolean readPowerSaveMode() {
        if (powerManager != null && powerManager.isPowerSaveMode()) return true;
        if (taskService == null) return false;
        try {
            String miui = taskService.executeShellCommandWithResult("settings get system POWER_SAVE_MODE_OPEN");
            String aosp = taskService.executeShellCommandWithResult("settings get global low_power");
            return "1".equals(miui == null ? null : miui.trim()) || "1".equals(aosp == null ? null : aosp.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开关开启且不在省电模式：熄屏时间设为最大值；否则恢复原值。
     * 开关已关闭时恢复后结束服务。
     */
    private void applyTimeout() {
        if (taskService == null) return;
        boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
        powerSaveMode = readPowerSaveMode();
        boolean keepOn = enabled && !powerSaveMode && !widgetPanelOpen;
        try {
            String current = taskService.executeShellCommandWithResult(
                "settings get system " + TIMEOUT_SETTING);
            current = current == null ? "" : current.trim();

            if (keepOn) {
                // 保存原值（已是最大值说明之前已设置过，不覆盖保存的原值）
                if (!current.isEmpty() && !current.equals("null") && !current.equals(MAX_TIMEOUT)) {
                    prefs.edit().putString(PREF_SAVED_TIMEOUT, current).apply();
                }
                if (!current.equals(MAX_TIMEOUT)) {
                    taskService.executeShellCommand("settings put system " + TIMEOUT_SETTING + " " + MAX_TIMEOUT);
                    Log.d(TAG, "✓ 背屏熄屏时间已设为最大值（原值 " + current + "）");
                }
            } else if (current.equals(MAX_TIMEOUT)) {
                // 只有在仍是我们设置的最大值时才恢复，避免覆盖用户之后手动修改的值
                String saved = prefs.getString(PREF_SAVED_TIMEOUT, DEFAULT_TIMEOUT);
                taskService.executeShellCommand("settings put system " + TIMEOUT_SETTING + " " + saved);
                Log.d(TAG, "✓ 背屏熄屏时间已恢复为 " + saved);
            }
        } catch (Throwable t) {
            Log.e(TAG, "设置背屏熄屏时间失败", t);
        }

        if (!enabled) stopSelf();
    }

    private void wakeRearScreen() {
        if (taskService == null) return;
        try {
            taskService.executeShellCommand("input -d " + REAR_DISPLAY_ID + " keyevent KEYCODE_WAKEUP");
        } catch (Throwable t) {
            Log.w(TAG, "点亮背屏失败: " + t.getMessage());
        }
    }

    private void createForegroundNotification() {
        String channelId = "mrss_core_service";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "MRSS内核服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MRSS目前正在运行");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("MRSS内核服务")
            .setContentText("MRSS目前正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(powerSaveReceiver);
        } catch (Exception ignored) {
        }
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
