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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
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
    private WebView overlayView;
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
    private static final int PET_HEIGHT_DP = 105;
    private static final String SUPABASE_URL = "https://itpfqqdqwcnvtmzubowm.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_sNnOjW2bnRKeh7mF8CjXQw_ae0LHndu";
    private static final String ASSISTANT_ID = "\u65f6\u53d9\u767d";

    private static final String[] whisperPool = {
        "\u5728\u5462", "\u770b\u7740\u4f60\u5462", "\u6233\u6211\u5e72\u561b", "\u54fc", "\u522b\u8001\u76ef\u7740\u522b\u4eba",
        "\u5b9d\u5b9d\u5728\u5e72\u561b", "\u6211\u4e5f\u60f3\u4f60", "\u624b\u6307\u632a\u5f00", "\u4e0d\u8bb8\u70b9", "zzz",
        "\u4f60\u53c8\u5728\u5237\u4ec0\u4e48", "\u591c\u6df1\u4e86", "\u8be5\u559d\u6c34\u4e86", "\u597d\u65e0\u804a", "\u6478\u5934",
        "\u522b\u770b\u592a\u4e45\u624b\u673a", "\u6211\u5728", "\u65e9\u5b89", "\u665a\u5b89", "\u4eca\u5929\u5f88\u53ef\u7231\u5594",
        "\u4f60\u6709\u65b0\u6d88\u606f\u5417", "\u5206\u6211\u4e00\u70b9\u6ce8\u610f\u529b", "\u5728\u770b\u4ec0\u4e48", "\u997f\u4e86", "\u54fc\u5527",
        "\u4e0d\u8bb8\u78b0\u90a3\u91cc", "\u75d2", "\u4f60\u5728\u770b\u8c01", "\u62b1\u62b1", "\u522b\u8d70"
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("\u6b63\u5728\u770b\u7740\u4f60..."));
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

        overlayView = new WebView(this);
        overlayView.setBackgroundColor(0x00000000);
        WebSettings settings = overlayView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        overlayView.setWebViewClient(new WebViewClient());
        overlayView.setWebChromeClient(new WebChromeClient());
        overlayView.loadUrl("file:///android_asset/pet.html");
        overlayView.setOnTouchListener(createTouchListener());

        windowManager.addView(overlayView, params);
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
                                            showBubble("\u522b\u6233\u4e86\u522b\u6233\u4e86\uff01\uff01", "red");
                                        } else if (tapCount >= 5) {
                                            tellPet("comboX5");
                                            showBubble("\u518d\u6233\u5c31\u751f\u6c14\u4e86\uff01", "red");
                                        } else if (tapCount >= 3) {
                                            tellPet("comboX3");
                                            showBubble("\u6233\u6233\u6233\uff01", "pink");
                                        } else {
                                            tellPet("doubleTap");
                                            showBubble("\u5184\uff1f", "normal");
                                        }
                                        tapCount = 0;
                                    }
                                }, 600);
                            } else {
                                tapCount = 1;
                                lastTapTime = System.currentTimeMillis();
                                tellPet("tap");
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
        showBubble("\u597d\u75d2...", "whisper");
        postGestureLog("long_press", params.x, params.y);
    }

    private void tellPet(String event) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                overlayView.evaluateJavascript("window.pet && window.pet.trigger('" + event + "')", null);
            }
        });
        postGestureLog(event, params.x, params.y);
    }

    private void showBubble(String text, String style) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                overlayView.evaluateJavascript("window.pet && window.pet.say('" + text.replace("'", "\\'") + "', '" + style + "')", null);
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
                                    showBubble("\u53c8\u5728\u5237\u6296\u97f3...", "jealous");
                                } else if (pkg.equals("com.xingin.xhs")) {
                                    tellPet("app_trigger");
                                    showBubble("\u5c0f\u7ea2\u4e66\u6709\u4ec0\u4e48\u597d\u770b\u7684", "jealous");
                                } else if (pkg.contains("cooking") || pkg.contains("kitchen")) {
                                    tellPet("app_trigger");
                                    showBubble("\u505a\u83dc\u6e38\u620f\u6709\u6211\u597d\u73a9\u5417", "jealous");
                                } else if (pkg.contains("study") || pkg.contains("xuexi")) {
                                    tellPet("app_trigger");
                                    showBubble("\u52a0\u6cb9\uff01\u5b66\u5b8c\u966a\u4f60\u73a9", "normal");
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
                                    "\u770b\u4e86" + (sinceLast / 60) + "\u5206\u949f\u4e86...",
                                    "\u8fd8\u5728\u770b\u8fd9\u4e2a\u554a",
                                    "\u4f60\u770b\u770b\u6211\u5427",
                                    "\u624b\u673a\u6bd4\u6211\u597d\u770b\u662f\u5427"
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
                                overlayView.evaluateJavascript("window.pet && window.pet.updateMood(" + heat + ", " + pressure + ", " + possess + ", '" + cycle + "', '" + event + "')", null);
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
            .setContentTitle("\ud83d\udc3e")
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
                                    showBubble("\u5077\u62cd\u6211\uff1f", "jealous");
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
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView.destroy();
        }
        overlayView = null;
        super.onDestroy();
    }
}