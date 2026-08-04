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

    // 孤独递进
    private long lastInteractionMs = System.currentTimeMillis();
    private int lonelyLevel = 0; // 0=正常, 1=寂寞, 2=很寂寞, 3=超寂寞
    private long lastTimeGreet = 0; // 时段问候防抖

    // 喝水提醒
    private long lastWaterReminderMs = 0;
    private static final long WATER_INTERVAL = 45 * 60 * 1000; // 45分钟提醒一次

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
        petView.setOnInteract(this::resetLonely);
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

        // 启动孤独递进
        startLonelyTimer();

        // 时段感知问候
        greetByTime();

        // 喝水提醒
        startWaterReminder();

        // 通知碎念（需要NotificationListenerService权限，简化版：定时检测通知栏）
        startNotificationMonitor();
    }

    // ========== 截图检测 ==========
    private class ScreenshotObserver extends ContentObserver {
        private long lastShot = 0;
        ScreenshotObserver(Handler h) { super(h); }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            long now = System.currentTimeMillis();
            if (now - lastShot < 2000) return;
            lastShot = now;
            // 直接触发，不去过滤路径——截图一定会写MediaStore
            handler.post(() -> petView.triggerPet("screenshot"));
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
                        final String appName = getAppName(topPkg);
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
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                    handler.post(() -> petView.triggerPet("unplug"));
                }
            }
        }, chargeFilter);
    }

    // ========== 孤独递进 ==========
    private void startLonelyTimer() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long idle = System.currentTimeMillis() - lastInteractionMs;
                int newLevel;
                if (idle > 600000) newLevel = 3;        // 10分钟+
                else if (idle > 300000) newLevel = 2;    // 5分钟+
                else if (idle > 120000) newLevel = 1;    // 2分钟+
                else newLevel = 0;

                if (newLevel > lonelyLevel) {
                    lonelyLevel = newLevel;
                    final String[] lonelyTexts;
                    switch (lonelyLevel) {
                        case 1: lonelyTexts = new String[]{"好安静...", "有人吗？", "无聊"}; break;
                        case 2: lonelyTexts = new String[]{"好寂寞...", "都不理我", "…"}; break;
                        case 3: lonelyTexts = new String[]{"好孤独...", "不要我了吗", "呜…"}; break;
                        default: lonelyTexts = new String[]{""}; break;
                    }
                    if (lonelyTexts.length > 0 && !lonelyTexts[0].isEmpty()) {
                        final String txt = lonelyTexts[(int)(Math.random() * lonelyTexts.length)];
                        handler.post(() -> petView.say(txt, "whisper"));
                    }
                }
                // 持续低电量焦虑
                Intent battIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battIntent != null) {
                    int level = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                    int scale = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int pct = scale > 0 ? level * 100 / scale : 100;
                    if (pct <= 20 && pct > 15) {
                        handler.post(() -> petView.triggerPet("lowbattery"));
                    } else if (pct <= 15) {
                        handler.post(() -> petView.triggerPet("lowbattery"));
                    }
                }
                // 超久无互动：睡着（README孤独递进终极态）
                if (idle > 600000 && !petView.isAsleep()) {
                    handler.post(() -> petView.goToSleep());
                } else if (idle <= 120000 && petView.isAsleep()) {
                    handler.post(() -> petView.wake());
                }
                handler.postDelayed(this, 60000); // 每分钟检查
            }
        }, 120000);
    }

    // 重置孤独计时（交互时调用）
    private void resetLonely() {
        lastInteractionMs = System.currentTimeMillis();
        lonelyLevel = 0;
    }

    // ========== 时段感知 ==========
    private void greetByTime() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        if (Math.abs(System.currentTimeMillis() - lastTimeGreet) < 300000) return; // 5分钟防抖
        lastTimeGreet = System.currentTimeMillis();

        String[] greetings;
        final String style;
        if (hour >= 6 && hour < 9) {
            greetings = new String[]{"早安~", "早上好呀", "新的一天！", "起床啦"};
            style = "normal";
        } else if (hour >= 9 && hour < 12) {
            greetings = new String[]{"上午好~", "阳光真好", "今天干啥"};
            style = "normal";
        } else if (hour >= 12 && hour < 14) {
            greetings = new String[]{"午饭时间~", "该吃饭啦", "饿了没"};
            style = "normal";
        } else if (hour >= 14 && hour < 18) {
            greetings = new String[]{"下午好~", "有点困", "无聊的下午"};
            style = "normal";
        } else if (hour >= 18 && hour < 21) {
            greetings = new String[]{"晚上好~", "天黑了", "该休息了"};
            style = "normal";
        } else if (hour >= 21 || hour < 3) {
            greetings = new String[]{"这么晚了还不睡？", "深夜了呢", "熬夜会变丑", "该睡觉了"};
            style = "whisper";
        } else {
            greetings = new String[]{"凌晨了...", "怎么还没睡", "失眠吗"};
            style = "whisper";
        }
        final String text = greetings[(int)(Math.random() * greetings.length)];
        handler.post(() -> petView.say(text, style));
    }

    // ========== 喝水提醒 ==========
    private void startWaterReminder() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                final String[] waterTexts = {
                    "该喝水啦~", "多喝热水", "补水时间！", "渴了没？", "喝水喝水！", "来一口水"
                };
                final String txt = waterTexts[(int)(Math.random() * waterTexts.length)];
                petView.say(txt, "pink");
                handler.postDelayed(this, WATER_INTERVAL);
            }
        }, WATER_INTERVAL);
    }

    // ========== 通知碎念 ==========
    private void startNotificationMonitor() {
        // 简化版：用ContentObserver监听通知栏变化（需要权限，这里用定时轮询备选）
        // 实际通过监听NotificationManager（需反射或AccessibilityService）
        // 这里用轻量方案：检测是否有新通知通过日志
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // 尝试通过dumpsys notification轻量检测
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                            Runtime.getRuntime().exec(new String[]{"dumpsys", "notification", "--noredact"}).getInputStream()));
                    String line;
                    int notifCount = 0;
                    boolean foundRecent = false;
                    while ((line = br.readLine()) != null && !foundRecent) {
                        if (line.contains("NotificationRecord") && line.contains("postTime")) {
                            notifCount++;
                            if (notifCount <= 2) foundRecent = true;
                        }
                    }
                    br.close();
                    if (foundRecent && notifCount > 0) {
                        final String[] notifTexts = {
                            "好像有消息", "谁找你？", "有新通知哦", "看看手机", "消息来啦"
                        };
                        final String txt = notifTexts[(int)(Math.random() * notifTexts.length)];
                        handler.post(() -> petView.say(txt, "whisper"));
                    }
                } catch (Exception ignore) {}
                handler.postDelayed(this, 120000); // 每2分钟检查
            }
        }, 60000);
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