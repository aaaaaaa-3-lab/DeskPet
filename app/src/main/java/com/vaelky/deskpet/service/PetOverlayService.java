package com.vaelky.deskpet.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
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
        petView.say("我在这儿~", "normal");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        petView = null;
        super.onDestroy();
    }
}