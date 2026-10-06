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
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import rikka.shizuku.Shizuku;

/**
 * V3.5: 未投放应用时常亮服务
 * 以500ms间隔持续发送KEYCODE_WAKEUP唤醒背屏
 * 背屏被用户休眠（如双击背屏熄屏）时停止唤醒，背屏重新亮起后恢复
 * ⚠️ 警告：可能导致烧屏和额外耗电
 */
public class AlwaysWakeUpService extends Service {
    private static final String TAG = "AlwaysWakeUpService";
    private static final int NOTIFICATION_ID = 1001; // 与其他Service共用ID
    // 500ms足以阻止背屏超时熄屏（与RearScreenKeeperService一致），间隔越短越容易打断双击熄屏
    private static final int WAKEUP_INTERVAL_MS = 500;
    // 背屏重新亮起后需持续亮屏这么久才恢复唤醒，避免熄屏/亮屏过渡期间误唤醒
    private static final long RESUME_DELAY_MS = 1500;
    private static final int REAR_DISPLAY_ID = 1;

    private ITaskService taskService;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private volatile boolean isRunning = false;
    private SharedPreferences prefs;
    private DisplayManager displayManager;
    // 背屏本次亮屏的起始时间，0表示背屏处于休眠
    private long rearOnSinceMs = 0;
    
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
                
                // 背屏被用户休眠（双击熄屏等）时不唤醒；重新亮起并稳定后再恢复
                try {
                    if (taskService != null && shouldWakeRearScreen()) {
                        taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "发送wakeup失败: " + t.getMessage());
                }
                
                // 继续下一轮
                wakeupHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        };
        
        rearOnSinceMs = 0;
        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Wakeup loop started (" + WAKEUP_INTERVAL_MS + "ms interval)");
    }

    /**
     * 背屏亮着且已稳定亮屏RESUME_DELAY_MS以上时才唤醒。
     * 唤醒循环本身会阻止背屏超时熄屏，因此背屏熄灭说明是用户主动休眠（双击背屏/电源键），此时不应再唤醒。
     */
    private boolean shouldWakeRearScreen() {
        if (isRearScreenAsleep()) {
            if (rearOnSinceMs != 0) Log.d(TAG, "⏸ 背屏已休眠，暂停唤醒");
            rearOnSinceMs = 0;
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (rearOnSinceMs == 0) {
            rearOnSinceMs = now;
            Log.d(TAG, "▶ 背屏已亮起，" + RESUME_DELAY_MS + "ms后恢复唤醒");
        }
        return now - rearOnSinceMs >= RESUME_DELAY_MS;
    }

    /**
     * 背屏是否处于休眠（熄屏/AOD）状态。
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

