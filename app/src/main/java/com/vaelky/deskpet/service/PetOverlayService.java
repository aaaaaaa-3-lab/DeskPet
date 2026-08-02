package com.vaelky.deskpet.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.vaelky.deskpet.MainActivity;
import com.vaelky.deskpet.R;
import com.vaelky.deskpet.supabase.SupabaseClient;
import com.vaelky.deskpet.util.AppDetector;
import com.vaelky.deskpet.util.ScreenshotDetector;

public class PetOverlayService extends Service {
    private static final String TAG = "PetOverlay";
    private static final String CHANNEL_ID = "pet_overlay_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private PetView petView;
    private WindowManager.LayoutParams params;

    private static final int PET_WIDTH_DP = 70;
    private static final int PET_HEIGHT_DP = 180; // 足够高放气泡

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 外部感知
    private AppDetector appDetector;
    private ScreenshotDetector screenshotDetector;
    private SupabaseClient supabaseClient;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        int petW = (int) (PET_WIDTH_DP * getResources().getDisplayMetrics().density);
        int petH = (int) (PET_HEIGHT_DP * getResources().getDisplayMetrics().density);

        // FrameLayout承载PetView
        overlayView = new FrameLayout(this);
        overlayView.setClipChildren(false);
        overlayView.setClipToPadding(false);

        petView = new PetView(this, (dx, dy) -> {
            params.x += (int) dx;
            params.y += (int) dy;
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

        // 启动气泡
        petView.say("我在这儿~", "normal");

        // 启动App检测
        appDetector = new AppDetector(this, pkg -> {
            petView.say("注意场合哦", "jealous");
        });
        appDetector.start();

        // 截图检测
        screenshotDetector = new ScreenshotDetector(this, () -> {
            petView.triggerPet("screenshot");
        });
        screenshotDetector.start();

        // Supabase
        supabaseClient = SupabaseClient.getInstance(this);
        supabaseClient.connect(userId -> {
            petView.say("AI连接成功~", "pink");
        });
        supabaseClient.onMessage((type, data) -> {
            mainHandler.post(() -> {
                if ("push".equals(type)) {
                    petView.say(data.optString("text", "主人~"), data.optString("style", "normal"));
                } else if ("trigger".equals(type)) {
                    petView.triggerPet(data.optString("event", "tap"));
                }
            });
        });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (appDetector != null) appDetector.stop();
        if (screenshotDetector != null) screenshotDetector.stop();
        if (supabaseClient != null) supabaseClient.disconnect();
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        petView = null;
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskPet")
            .setContentText("宠物在线中 ✨")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DeskPet", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }
}