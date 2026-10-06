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
 * - 两块屏都亮时按电源键，系统会同时熄灭两块屏。主屏熄灭（SCREEN_OFF）时若背屏仍亮着，
 *   稍后重新点亮背屏。背屏的显示状态要几秒后才变为熄灭，所以SCREEN_OFF时读到的仍是熄灭前的状态。
 *   若只是主屏超时熄灭，背屏本就亮着，再发wakeup不会有任何影响。
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
    // 主屏熄灭后延迟点亮背屏，等系统处理完电源键
    private static final long REWAKE_DELAY_MS = 500;

    private ITaskService taskService;
    private SharedPreferences prefs;
    private PowerManager powerManager;
    private DisplayManager displayManager;
    private Handler mainHandler;
    private boolean bound = false;

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
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠️ TaskService disconnected");
            taskService = null;
            bound = false;
            mainHandler.postDelayed(AlwaysWakeUpService.this::bindTaskService, 1000);
        }
    };

    private final BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "省电模式变化: " + isPowerSaveMode());
            applyTimeout();
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
            boolean rearWasOn = getRearState() == Display.STATE_ON
                || SystemClock.elapsedRealtime() - rearOffAtMs < REAR_RECENTLY_ON_MS;
            if (rearWasOn && !isPowerSaveMode()) {
                Log.d(TAG, "🔌 主屏熄灭时背屏亮着，重新点亮背屏");
                mainHandler.postDelayed(AlwaysWakeUpService.this::wakeRearScreen, REWAKE_DELAY_MS);
            }
        }
    };

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

    private boolean isPowerSaveMode() {
        return powerManager != null && powerManager.isPowerSaveMode();
    }

    /**
     * 开关开启且不在省电模式：熄屏时间设为最大值；否则恢复原值。
     * 开关已关闭时恢复后结束服务。
     */
    private void applyTimeout() {
        if (taskService == null) return;
        boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
        boolean keepOn = enabled && !isPowerSaveMode();
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
