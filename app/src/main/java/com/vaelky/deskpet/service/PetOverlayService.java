package com.vaelky.deskpet.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.vaelky.deskpet.MainActivity;

public class PetOverlayService extends Service {
    private static final String CHANNEL_ID = "pet_overlay";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private PetView petView;
    private WindowManager.LayoutParams params;

    private static final int PET_WIDTH_DP = 70;
    private static final int PET_HEIGHT_DP = 180;

    // 感知模块
    private ScreenshotObserver screenshotObserver;
    private String lastForegroundPkg = "";
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DeskPet", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskPet")
            .setContentText("在线中 ✨")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION_ID, notif);

        int petW = (int) (PET_WIDTH_DP * getResources().getDisplayMetrics().density);
        int petH = (int) (PET_HEIGHT_DP * getResources().getDisplayMetrics().density);

        overlayView = new FrameLayout(this);
        overlayView.setClipChildren(false);
        overlayView.setClipToPadding(false);

        petView = new PetView(this, (dx, dy) -> {
            params.x -= (int) dx;
            params.y -= (int) dy;
            windowManager.updateViewLayout(overlayView, params);
        });
        overlayView.addView(petView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        params = new WindowManager.LayoutParams(
            petW, petH,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 0;
        params.y = 0;

        windowManager.addView(overlayView, params);
        petView.say("我在这儿~", "normal");

        // 启动截图监听
        screenshotObserver = new ScreenshotObserver(handler);
        getContentResolver().registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver);

        // 启动前台App检测
        startForegroundAppMonitor();

        // 检测充电状态
        checkCharging();
    }

    // ========== 截图检测 ==========
    private class ScreenshotObserver extends ContentObserver {
        private long lastShot = 0;
        ScreenshotObserver(Handler h) { super(h); }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            long now = System.currentTimeMillis();
            if (now - lastShot < 1000) return; // 去抖
            lastShot = now;
            // 检查路径是否包含screenshot
            String path = uri != null ? uri.toString().toLowerCase() : "";
            if (path.contains("screenshot") || path.contains("screenshots")) {
                handler.post(() -> petView.triggerPet("screenshot"));
            }
        }
    }

    // ========== 前台App检测 ==========
    private void startForegroundAppMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 用dumpsys window快速获取前台包名（需要权限，这里用usage stats作为备选）
                // 简化：通过实时检测前台activity
                detectForegroundApp();
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void detectForegroundApp() {
        // 这里用简单的系统方法检测前台App
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                java.util.List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                        now - 10000, now);
                if (stats != null && !stats.isEmpty()) {
                    String topPkg = null;
                    long latest = 0;
                    for (android.app.usage.UsageStats s : stats) {
                        if (s.getLastTimeUsed() > latest) {
                            latest = s.getLastTimeUsed();
                            topPkg = s.getPackageName();
                        }
                    }
                    if (topPkg != null && !topPkg.equals(lastForegroundPkg) && !topPkg.equals(getPackageName())) {
                        lastForegroundPkg = topPkg;
                        String appName = getAppName(topPkg);
                        handler.post(() -> petView.say("在看" + appName + "？", "whisper"));
                    }
                }
            }
        }
    }

    private String getAppName(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg.substring(Math.max(0, pkg.lastIndexOf('.') + 1));
        }
    }

    // ========== 充电检测 ==========
    private void checkCharging() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
            if (charging) {
                handler.post(() -> petView.triggerPet("charging"));
            }
        }
        // 监听充电状态变化
        IntentFilter chargeFilter = new IntentFilter();
        chargeFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        chargeFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                    handler.post(() -> petView.triggerPet("charging"));
                }
            }
        }, chargeFilter);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        if (screenshotObserver != null) {
            getContentResolver().unregisterContentObserver(screenshotObserver);
        }
        handler.removeCallbacksAndMessages(null);
        overlayView = null;
        petView = null;
        super.onDestroy();
    }
}