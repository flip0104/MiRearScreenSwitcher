package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import rikka.shizuku.Shizuku;

/**
 * V3.5: 未投放应用时常亮服务
 * 以100ms间隔持续发送KEYCODE_WAKEUP唤醒背屏
 * ⚠️ 警告：可能导致烧屏和额外耗电
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    private static final int WAKEUP_INTERVAL_MS = 100; // 100ms间隔
    private static final long CHECK_REAR_INTERVAL_MS = 2000; // 每2秒检查背屏内容
    private static final int REAR_DISPLAY_ID = 1;
    // 小米背屏官方组件（小部件/时钟等），显示时不唤醒
    private static final String[] XIAOMI_WIDGET_PACKAGES = {
        "com.xiaomi.subscreencenter",
        "com.xiaomi.mirror",
    };

    private ITaskService taskService;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private volatile boolean isRunning = false;
    private SharedPreferences prefs;
    private DisplayManager displayManager;

    // 背屏内容检查在后台线程执行（shell命令较慢，不阻塞100ms唤醒循环）
    private HandlerThread checkThread;
    private Handler checkHandler;
    private volatile boolean xiaomiWidgetOnRear = false;

    private final Runnable checkRearRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            try {
                if (taskService != null) {
                    String rearApp = taskService.getForegroundAppOnDisplay(REAR_DISPLAY_ID);
                    boolean widget = isXiaomiWidget(rearApp);
                    if (widget != xiaomiWidgetOnRear) {
                        Log.d(TAG, widget ? "⏸ 背屏为小米组件，暂停唤醒: " + rearApp
                                          : "▶ 背屏非小米组件，恢复唤醒: " + rearApp);
                    }
                    xiaomiWidgetOnRear = widget;
                }
            } catch (Throwable t) {
                Log.w(TAG, "背屏内容检查失败: " + t.getMessage());
            }
            checkHandler.postDelayed(this, CHECK_REAR_INTERVAL_MS);
        }
    };
    
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
            
            // TaskService连接后开始发送wakeup
            startWakeupLoop();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "⚠️ TaskService disconnected");
            taskService = null;
            
            // 断开后尝试重连
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🔄 尝试重新绑定TaskService...");
                bindTaskService();
            }, 1000);
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "📱 onCreate");
        
        prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
        wakeupHandler = new Handler(Looper.getMainLooper());
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        checkThread = new HandlerThread("AlwaysWakeUpCheck");
        checkThread.start();
        checkHandler = new Handler(checkThread.getLooper());

        // 创建前台通知
        createForegroundNotification();
        
        // 绑定TaskService
        bindTaskService();
    }
    
    private void bindTaskService() {
        try {
            if (taskService != null) {
                Log.d(TAG, "TaskService already bound");
                return;
            }
            
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            Log.d(TAG, "🔗 开始绑定TaskService...");
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "绑定TaskService失败", e);
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
        Log.d(TAG, "✓ 前台服务已启动");
    }
    
    private void startWakeupLoop() {
        if (isRunning) {
            Log.w(TAG, "⚠️ Wakeup loop already running");
            return;
        }
        
        isRunning = true;
        
        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                // 检查开关状态
                boolean enabled = prefs.getBoolean("always_wakeup_enabled", false);
                if (!enabled) {
                    Log.d(TAG, "开关已关闭，停止wakeup循环");
                    stopSelf();
                    return;
                }
                
                // 背屏显示小米组件或处于休眠状态时，不唤醒
                // 发送wakeup命令
                try {
                    if (taskService != null && !xiaomiWidgetOnRear && !isRearScreenAsleep()) {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "发送wakeup失败: " + t.getMessage());
                }
                
                // 100ms后继续
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        };
        
        // 先检查一次背屏内容，再开始唤醒循环
        checkHandler.post(checkRearRunnable);
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup loop started (100ms interval)");
    }

    private static boolean isXiaomiWidget(String rearApp) {
        if (rearApp == null) return false;
        for (String pkg : XIAOMI_WIDGET_PACKAGES) {
            if (rearApp.startsWith(pkg)) return true;
        }
        return false;
    }

    /**
     * 背屏是否处于休眠（熄屏/AOD）状态。
     * 唤醒循环本身会阻止背屏超时熄屏，因此背屏熄灭说明是用户主动休眠（如电源键），此时不应再唤醒。
     */
    private boolean isRearScreenAsleep() {
        if (displayManager == null) return false;
        Display rear = displayManager.getDisplay(REAR_DISPLAY_ID);
        if (rear == null) return false;
        int state = rear.getState();
        return state == Display.STATE_OFF
            || state == Display.STATE_DOZE
            || state == Display.STATE_DOZE_SUSPEND;
    }

    private void stopWakeupLoop() {
        isRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
        if (checkHandler != null) {
            checkHandler.removeCallbacks(checkRearRunnable);
        }
        Log.d(TAG, "✓ Wakeup loop stopped");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "🔴 onDestroy");
        
        stopWakeupLoop();
        if (checkThread != null) {
            checkThread.quitSafely();
        }

        // 解绑TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                Log.d(TAG, "✓ TaskService unbound");
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

