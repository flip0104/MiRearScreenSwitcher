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
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

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
 * - 两块屏都亮时按电源键，系统会同时熄灭两块屏，需要重新点亮背屏。为了尽量缩短背屏熄灭的时间：
 *   1. 通过 getevent 直接监听电源键（比SCREEN_OFF广播早约0.5秒），松开后立即多次点亮背屏
 *   2. SCREEN_OFF 广播作为兜底
 *   背屏的显示状态要几秒后才变为熄灭，所以此时读到的仍是熄灭前的状态。
 *   若背屏本就亮着（例如只是主屏超时熄灭），再发wakeup不会有任何影响。
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
    // 主屏熄灭前这么久内背屏还亮着，就认为背屏是被电源键一起熄灭的
    private static final long REAR_RECENTLY_ON_MS = 1500;
    // 电源键松开后点亮背屏的时间点：系统熄屏可能稍晚于松开，多发几次（亮着时wakeup无影响）
    private static final long[] POWER_KEY_REWAKE_DELAYS_MS = {30, 200, 500};

    private ITaskService taskService;
    private SharedPreferences prefs;
    private PowerManager powerManager;
    private DisplayManager displayManager;
    private Handler mainHandler;
    private boolean bound = false;
    private volatile boolean destroyed = false;
    // 省电模式状态缓存（读取需要shell命令，熄屏路径上不能等）
    private volatile boolean powerSaveMode = false;
    private Thread powerKeyThread;

    // 背屏最近一次由亮转灭的时间
    private int lastRearState = Display.STATE_UNKNOWN;
    private long rearOffAtMs = 0;

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
            Log.d(TAG, "✓ TaskService connected");
            applyTimeout();
            startPowerKeyWatcher();
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

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != REAR_DISPLAY_ID) return;
            int state = getRearState();
            if (lastRearState == Display.STATE_ON && state != Display.STATE_ON) {
                rearOffAtMs = SystemClock.elapsedRealtime();
            }
            lastRearState = state;
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (rearWasOn() && !powerSaveMode) {
                Log.d(TAG, "🔌 主屏熄灭时背屏亮着，重新点亮背屏");
                wakeRearScreen();
            }
        }
    };

    private boolean rearWasOn() {
        return getRearState() == Display.STATE_ON
            || SystemClock.elapsedRealtime() - rearOffAtMs < REAR_RECENTLY_ON_MS;
    }

    /**
     * 在后台线程用 getevent 监听电源键。每次读取2个事件（按键+SYN），按下和松开各返回一次。
     */
    private void startPowerKeyWatcher() {
        if (powerKeyThread != null) return;
        powerKeyThread = new Thread(() -> {
            String device = null;
            while (!destroyed) {
                ITaskService ts = taskService;
                if (ts == null) {
                    SystemClock.sleep(1000);
                    continue;
                }
                try {
                    if (device == null) {
                        device = findPowerKeyDevice(ts);
                        if (device == null) {
                            Log.w(TAG, "未找到电源键输入设备，仅使用SCREEN_OFF兜底");
                            return;
                        }
                        Log.d(TAG, "电源键输入设备: " + device);
                    }
                    String events = ts.executeShellCommandWithResult("getevent -lqc 2 " + device);
                    if (events != null && events.contains("KEY_POWER") && events.contains("UP")
                            && !destroyed && rearWasOn() && !powerSaveMode) {
                        Log.d(TAG, "🔌 电源键松开，重新点亮背屏");
                        for (long delay : POWER_KEY_REWAKE_DELAYS_MS) {
                            mainHandler.postDelayed(this::wakeRearScreen, delay);
                        }
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
     * 从 getevent -pl 中找出带 KEY_POWER 且不是触摸屏（无ABS_MT）的输入设备
     */
    private static String findPowerKeyDevice(ITaskService ts) throws Exception {
        String output = ts.executeShellCommandWithResult("getevent -pl");
        if (output == null) return null;
        String device = null;
        boolean hasPowerKey = false;
        boolean isTouch = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("add device")) {
                if (device != null && hasPowerKey && !isTouch) return device;
                int idx = line.indexOf("/dev/input/");
                device = idx >= 0 ? line.substring(idx).trim() : null;
                hasPowerKey = false;
                isTouch = false;
            } else if (line.contains("KEY_POWER")) {
                hasPowerKey = true;
            } else if (line.contains("ABS_MT")) {
                isTouch = true;
            }
        }
        return device != null && hasPowerKey && !isTouch ? device : null;
    }

    private int getRearState() {
        Display rear = displayManager.getDisplay(REAR_DISPLAY_ID);
        return rear == null ? Display.STATE_UNKNOWN : rear.getState();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        createForegroundNotification();

        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(MIUI_POWER_SAVE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(powerSaveReceiver, filter);
        }

        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        lastRearState = getRearState();
        displayManager.registerDisplayListener(displayListener, mainHandler);

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
        boolean keepOn = enabled && !powerSaveMode;
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
        for (BroadcastReceiver receiver : new BroadcastReceiver[] {powerSaveReceiver, screenOffReceiver}) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
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
