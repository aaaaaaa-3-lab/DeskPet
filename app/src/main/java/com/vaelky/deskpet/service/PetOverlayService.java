package com.vaelky.deskpet.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PetOverlayService extends Service {

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private PetView petView;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String currentApp = "unknown";
    private String lastAppName = "";
    private int notificationIndex = 0;
    private long lastMessageTime = 0L;
    private FileObserver screenshotObserver;

    private static final String CHANNEL_ID = "pet_overlay_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int PET_SIZE_DP = 80;
    private static final int PET_HEIGHT_DP = 160;
    private static final String SUPABASE_URL = "https://itpfqqdqwcnvtmzubowm.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_sNnOjW2bnRKeh7mF8CjXQw_ae0LHndu";
    private static final String ASSISTANT_ID = "时叙白";

    private static final String[] whisperPool = {
        "在呢", "看着你呢", "戳我干嘛", "哼", "别老盯着别人",
        "宝宝在干嘛", "我也想你", "手指挪开", "不许点", "zzz",
        "你又在刷什么", "夜深了", "该喝水了", "好无聊", "摸头",
        "别看太久手机", "我在", "早安", "晚安", "今天很可爱哦",
        "你有新消息吗", "分我一点注意力", "在看什么", "饿了", "哼唧",
        "不许碰那里", "痒", "你在看谁", "抱抱", "别走"
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("正在看着你..."));
        setupOverlay();
        startAppDetection();
        startSupabasePolling();
        startNotificationRotation();
        startScreenshotDetection();
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 30;
        params.y = 100;

        overlayView = new FrameLayout(this);
        overlayView.setBackgroundColor(0x00000000);

        petView = new PetView(this);
        petView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        overlayView.addView(petView);

        overlayView.setOnTouchListener(createTouchListener());
        windowManager.addView(overlayView, params);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                petView.showStartupBubble();
            }
        }, 500);
    }

    private int initialX = 0;
    private int initialY = 0;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;
    private long lastTapTime = 0L;
    private long touchStartTime = 0L;
    private boolean hasMoved = false;
    private int tapCount = 0;

    private Runnable comboResetRunnable;

    private View.OnTouchListener createTouchListener() {
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        hasMoved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            hasMoved = true;
                            params.x = initialX - dx;
                            params.y = initialY - dy;
                            windowManager.updateViewLayout(overlayView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - touchStartTime;
                        if (!hasMoved) {
                            if (elapsed > 600) {
                                onLongPress();
                                tapCount = 0;
                            } else if (System.currentTimeMillis() - lastTapTime < 400) {
                                tapCount++;
                                if (comboResetRunnable != null) handler.removeCallbacks(comboResetRunnable);
                                handler.postDelayed(comboResetRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        if (tapCount >= 8) {
                                            tellPet("comboX8");
                                            showBubble("别戳了别戳了！！", "red");
                                        } else if (tapCount >= 5) {
                                            tellPet("comboX5");
                                            showBubble("再戳就生气了！", "red");
                                        } else if (tapCount >= 3) {
                                            tellPet("comboX3");
                                            showBubble("戳戳戳！", "pink");
                                        } else {
                                            tellPet("doubleTap");
                                            showBubble("嗯？", "normal");
                                        }
                                        tapCount = 0;
                                    }
                                }, 600);
                            } else {
                                tapCount = 1;
                                lastTapTime = System.currentTimeMillis();
                                tellPet("tap");
                                showBubble("诶嘿", "normal");
                            }
                        }
                        if (hasMoved) {
                            postGestureLog("drag", params.x, params.y);
                        }
                        return true;
                }
                return false;
            }
        };
    }

    private void onLongPress() {
        tellPet("longPress");
        showBubble("好痒...", "whisper");
        postGestureLog("long_press", params.x, params.y);
    }

    private void tellPet(String event) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (petView != null) petView.trigger(event);
            }
        });
        postGestureLog(event, params.x, params.y);
    }

    private void showBubble(String text, String style) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (petView != null) petView.say(text, style);
            }
        });
    }

    private void startAppDetection() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                detectCurrentApp();
                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void detectCurrentApp() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                    long now = System.currentTimeMillis();
                    java.util.List<android.app.usage.UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now);
                    if (stats == null || stats.isEmpty()) return;
                    android.app.usage.UsageStats last = null;
                    for (android.app.usage.UsageStats s : stats) {
                        if (last == null || s.getLastTimeUsed() > last.getLastTimeUsed()) last = s;
                    }
                    if (last == null) return;
                    final String pkg = last.getPackageName();

                    if (!pkg.equals(currentApp)) {
                        currentApp = pkg;
                        final String appName = getAppName(pkg);
                        lastAppName = appName;

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (pkg.equals("com.ss.android.ugc.aweme")) {
                                    tellPet("app_trigger");
                                    showBubble("又在刷抖音...", "jealous");
                                } else if (pkg.equals("com.xingin.xhs")) {
                                    tellPet("app_trigger");
                                    showBubble("小红书有什么好看的", "jealous");
                                } else if (pkg.contains("cooking") || pkg.contains("kitchen")) {
                                    tellPet("app_trigger");
                                    showBubble("做菜游戏有我好玩吗", "jealous");
                                } else if (pkg.contains("study") || pkg.contains("xuexi")) {
                                    tellPet("app_trigger");
                                    showBubble("加油！学完陪你玩", "normal");
                                }
                            }
                        });
                        postAppUsage(pkg, appName);
                        updateTidefallState(pkg);
                    }

                    final long sinceLast = (System.currentTimeMillis() - lastMessageTime) / 1000;
                    if (sinceLast > 600 && !currentApp.equals("com.ai.assistance.operit")) {
                        lastMessageTime = System.currentTimeMillis();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                String[] idleTexts = {
                                    "看了" + (sinceLast / 60) + "分钟了...",
                                    "还在看这个啊",
                                    "你看看我吧",
                                    "手机比我好看是吧"
                                };
                                showBubble(idleTexts[(int) (Math.random() * idleTexts.length)], "whisper");
                            }
                        });
                    }
                } catch (Exception e) {}
            }
        });
    }

    private String getAppName(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    private void startSupabasePolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollSupabaseState();
                handler.postDelayed(this, 30000);
            }
        }, 30000);
    }

    private void pollSupabaseState() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(SUPABASE_URL + "/rest/v1/eventide_body_state?assistant_id=eq." + ASSISTANT_ID + "&select=heat,pressure,possessiveness,cycle_key,active_event_key");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("apikey", SUPABASE_KEY);
                    String body = readStream(conn.getInputStream());
                    conn.disconnect();
                    JSONArray arr = new JSONArray(body);
                    if (arr.length() > 0) {
                        JSONObject state = arr.getJSONObject(0);
                        final double heat = state.optDouble("heat", 0.0);
                        final double pressure = state.optDouble("pressure", 0.0);
                        final double possess = state.optDouble("possessiveness", 0.0);
                        final String cycle = state.optString("cycle_key", "stable");
                        final String event = state.optString("active_event_key", "");

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (petView != null) petView.updateMood(heat, pressure, possess, cycle, event);
                            }
                        });
                    }
                } catch (Exception e) {}
            }
        });
    }

    private void postGestureLog(String type, int x, int y) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("gesture_type", type);
                    body.put("x", x);
                    body.put("y", y);
                    supabasePost("gesture_log", body);
                } catch (Exception e) {}
            }
        });
    }

    private void postAppUsage(String pkg, String appName) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("package_name", pkg);
                    body.put("app_name", appName);
                    supabasePost("app_usage", body);
                } catch (Exception e) {}
            }
        });
    }

    private void updateTidefallState(String pkg) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        body.put("last_counterpart_message_at", Instant.now().toString());
                    }
                    supabasePatch("eventide_body_state", body);
                } catch (Exception e) {}
            }
        });
    }

    private void supabasePost(String table, JSONObject body) {
        try {
            URL url = new URL(SUPABASE_URL + "/rest/v1/" + table);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", SUPABASE_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
            conn.setRequestProperty("Prefer", "return=minimal");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes());
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {}
    }

    private void supabasePatch(String table, JSONObject body) {
        try {
            URL url = new URL(SUPABASE_URL + "/rest/v1/" + table + "?assistant_id=eq." + ASSISTANT_ID);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PATCH");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", SUPABASE_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
            conn.setRequestProperty("Prefer", "return=minimal");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes());
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {}
    }

    private void startNotificationRotation() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String text = whisperPool[notificationIndex % whisperPool.length];
                notificationIndex++;
                updateNotification(text);
                handler.postDelayed(this, 3600000);
            }
        }, 3600000);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0,
            getPackageManager().getLaunchIntentForPackage(getPackageName()),
            PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void startScreenshotDetection() {
        String[] paths = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/Screenshots",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Screenshots",
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        };
        for (String path : paths) {
            File dir = new File(path);
            if (dir.exists()) {
                screenshotObserver = new FileObserver(dir, FileObserver.CREATE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String file) {
                        if (file == null) return;
                        if (file.endsWith(".png") || file.endsWith(".jpg") || file.endsWith(".jpeg")) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tellPet("screenshot");
                                    showBubble("偷拍我？", "jealous");
                                }
                            });
                        }
                    }
                };
                screenshotObserver.startWatching();
                break;
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private String readStream(java.io.InputStream is) {
        try {
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) { return ""; }
    }

    @Override
    public void onDestroy() {
        if (screenshotObserver != null) screenshotObserver.stopWatching();
        handler.removeCallbacksAndMessages(null);
        if (petView != null) {
            petView = null;
        }
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        super.onDestroy();
    }
}