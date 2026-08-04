package com.vaelky.deskpet.service;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

/**
 * PetView — 纯Canvas绘制，1:1复刻pet.html的SVG外观。
 * SVG坐标（300x200视口）映射到Canvas（width x height），保持比例。
 */
public class PetView extends View {

    // === 外观状态（对应pet.html的CSS类） ===
    private boolean blushOn = false;
    private boolean eyesClosed = false;
    private boolean mouthOpen = false;
    private String currentAnim = ""; // bouncing, wiggling, tilting, squishing
    private long animStartMs = 0;
    private long animDurationMs = 2200;

    // === 动画偏移 ===
    private float animTranslateY = 0;
    private float animRotation = 0;
    private float animScaleX = 1f, animScaleY = 1f;

    // === 气泡 ===
    private String bubbleText = "";
    private String bubbleStyle = "normal";
    private long bubbleStartMs = 0;
    private static final long BUBBLE_DURATION = 2800;

    // === Heat热度系统 ===
    private int heat = 0;           // 0-100，越戳越热
    private long lastHeatDecayMs = 0; // 热度衰减计时
    private float bodyTint = 0f;    // 0=正常暖粉, 1=羞红

    // === 自言自语 ===
    private long nextMurmurMs = 0;   // 下次自言自语时间

    // === 触摸（单击/双击/长按/连击） ===
    private int tapCount = 0;
    private long lastTapMs = 0;
    private long touchStartMs = 0;
    private float touchStartX = 0, touchStartY = 0;
    private boolean isDragging = false;
    private boolean isFling = false;
    private float dragVelocityX = 0, dragVelocityY = 0;
    private Runnable pendingTap = null;
    private Runnable pendingLongPress = null;
    private DragCallback dragCallback;
    private Runnable onInteract;

    // === 甩出爬回 (fling) 参数 ===
    private String state = "awake";                  // awake / sleeping / flingOut / flingBack
    private float flingTargetX = 0, flingTargetY = 0;
    private float animTranslateX = 0;
    private static final float FLING_THRESHOLD = 800f; // px/s
    private static final long FLING_OUT_MS = 500;
    private static final long FLING_BACK_MS = 1200;

    public interface DragCallback {
        void onDrag(float dx, float dy);
    }

    // === 眨眼定时器 ===
    private long nextBlinkMs = 0;

    public PetView(Context context, DragCallback dragCallback) {
        super(context);
        this.dragCallback = dragCallback;
        setWillNotDraw(false);
        scheduleNextBlink();
        // 热度衰减心跳：每2秒触发一次onDraw
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (heat > 0) { invalidate(); }
                postDelayed(this, 2000);
            }
        }, 2000);
    }

    public void setOnInteract(Runnable r) {
        this.onInteract = r;
    }
    // ==================== 外部调用（替代pet.trigger/say） ====================

    public void triggerPet(String event) {
        long now = System.currentTimeMillis();
        animStartMs = now;
        animDurationMs = 2200;
        blushOn = false;
        mouthOpen = false;

        String[] texts;
        String style = "normal";

        switch (event) {
            case "tap":
                currentAnim = "bouncing";
                texts = new String[]{"诶嘿", "嗯？", "戳我干嘛", "别闹~", "哈喽"};
                style = "normal";
                break;
            case "doubleTap":
                currentAnim = "wiggling";
                texts = new String[]{"呀~", "干嘛啦", "哎呀", "讨厌", "双击我？"};
                style = "pink";
                blushOn = true;
                break;
            case "longPress":
                currentAnim = "squishing";
                texts = new String[]{"好痒...", "别捏了", "痒死了", "松手啦", "唔..."};
                style = "whisper";
                blushOn = true;
                break;
            case "comboX3":
                currentAnim = "wiggling";
                texts = new String[]{"别戳啦", "够了够了", "停停停", "啊啊啊"};
                style = "pink";
                blushOn = true;
                break;
            case "comboX5":
                currentAnim = "squishing";
                texts = new String[]{"呜哇！！", "要坏了！！", "太用力了！！", "住手啊！！"};
                style = "red";
                blushOn = true;
                mouthOpen = true;
                break;
            case "comboX8":
                currentAnim = "bouncing";
                texts = new String[]{"要生气了！！", "我真生气了！！", "你再戳！！", "爆炸了！！"};
                style = "red";
                blushOn = true;
                mouthOpen = true;
                break;
            case "screenshot":
                currentAnim = "squishing";
                texts = new String[]{"偷拍我？", "又截图？", "不准截！", "拍什么拍", "？"};
                style = "jealous";
                blushOn = true;
                break;
            case "charging":
                currentAnim = "bouncing";
                texts = new String[]{"吃饱饱~", "充电中~", "香香~", "来劲了", "满血复活！"};
                style = "normal";
                break;
            case "afk_sleep":
                currentAnim = "";
                texts = new String[]{"zzZ…", "Zzz…", "呼…睡着了"};
                style = "whisper";
                eyesClosed = true;
                animDurationMs = 1000;
                break;
            case "wakeup":
                currentAnim = "bouncing";
                texts = new String[]{"唔…睡饱了", "早呀~", "嘿嘿", "精神满满"};
                style = "normal";
                eyesClosed = false;
                break;
            case "lowbattery":
                currentAnim = "squishing";
                texts = new String[]{"快没电了…", "要撑不住了", "呜呜电量低", "好慌"};
                style = "whisper";
                blushOn = true;
                break;
            case "yawn":
                currentAnim = "tilting";
                texts = new String[]{"哈啊…", "有点困", "想睡了"};
                style = "whisper";
                mouthOpen = true;
                break;
            case "sigh":
                currentAnim = "squishing";
                texts = new String[]{"唉…", "累啊", "叹口气"};
                style = "whisper";
                break;
            case "unplug":
                currentAnim = "tilting";
                texts = new String[]{"不充了？", "还没满呢", "唔…"};
                style = "whisper";
                break;
            default:
                currentAnim = "bouncing";
                texts = new String[]{"诶嘿"};
                style = "normal";
                break;
        }

        // 随机选一句
        String text = texts[(int)(Math.random() * texts.length)];
        say(text, style);

        // 热度升温
        lastHeatDecayMs = System.currentTimeMillis();
        int heatDelta;
        switch (event) {
            case "tap": heatDelta = 5; break;
            case "doubleTap": heatDelta = 12; break;
            case "longPress": heatDelta = 20; break;
            case "comboX3": heatDelta = 25; break;
            case "comboX5": heatDelta = 35; break;
            case "comboX8": heatDelta = 50; break;
            case "screenshot": heatDelta = 15; break;
            default: heatDelta = 3; break;
        }
        heat = Math.min(100, heat + heatDelta);
        // 热度影响外观：高温时身体变红
        if (heat >= 80) { bodyTint = 1f; }
        else if (heat >= 50) { bodyTint = heat / 100f; }

        // 通知Service：有交互了
        if (onInteract != null) onInteract.run();

        invalidate();

        // 自动重置
        postDelayed(() -> {
            if (System.currentTimeMillis() - animStartMs >= animDurationMs - 100) {
                currentAnim = "";
                blushOn = false;
                mouthOpen = false;
                invalidate();
            }
        }, animDurationMs);
    }

    public void say(String text, String style) {
        bubbleText = text;
        bubbleStyle = style != null ? style : "normal";
        bubbleStartMs = System.currentTimeMillis();
        invalidate();
        postDelayed(() -> {
            if (System.currentTimeMillis() - bubbleStartMs >= BUBBLE_DURATION - 100) {
                bubbleText = "";
                invalidate();
            }
        }, BUBBLE_DURATION);
    }

    public void showBubble(String text, String style) {
        say(text, style);
    }

    public void setBlush(boolean on) { blushOn = on; invalidate(); }
    public void setEyesClosed(boolean closed) { eyesClosed = closed; invalidate(); }
    public void setMouthOpen(boolean open) { mouthOpen = open; invalidate(); }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();

        // 眨眼
        if (now >= nextBlinkMs && currentAnim.isEmpty()) {
            eyesClosed = true;
            invalidate();
            postDelayed(() -> { eyesClosed = false; invalidate(); }, 150);
            scheduleNextBlink();
        }

        // 动画插值
        float t = currentAnim.isEmpty() ? 1f : Math.min(1f, (now - animStartMs) / (float) animDurationMs);
        animTranslateY = 0;
        animRotation = 0;
        animScaleX = 1f;
        animScaleY = 1f;
        this.animTranslateX = 0;

        // 甩出爬回：优先根据state计算水平位移
        if (state.equals("flingOut")) {
            applyFlingOut(t);
            animTranslateY = -(1 - t) * (1 - t) * 40; // 抛物线上升
        } else if (state.equals("flingBack")) {
            applyFlingBack(t);
        }

        if (!currentAnim.isEmpty() && t < 1f && state.equals("awake")) {
            switch (currentAnim) {
                case "bouncing": {
                    float bt = t * 2f; // bounce: quick up, slight overshoot
                    if (bt <= 1f) animTranslateY = -18 * cubicOut(bt);
                    else animTranslateY = -5 * (float) Math.sin((bt - 1f) * Math.PI);
                    break;
                }
                case "wiggling": {
                    animRotation = (float) (Math.sin(t * 2.5 * Math.PI) * 10 * (1 - t));
                    break;
                }
                case "tilting": {
                    animRotation = (float) (Math.sin(t * Math.PI) * 8 * (1 - t));
                    break;
                }
                case "squishing": {
                    if (t < 0.3f) { float s = t / 0.3f; animScaleX = 1f + 0.18f * s; animScaleY = 1f - 0.18f * s; }
                    else if (t < 0.6f) { float s = (t - 0.3f) / 0.3f; animScaleX = 1.18f - 0.25f * s; animScaleY = 0.82f + 0.26f * s; }
                    else { float s = (t - 0.6f) / 0.4f; animScaleX = 1f + (1 - s) * (animScaleX - 1f); animScaleY = 1f + (1 - s) * (animScaleY - 1f); }
                    break;
                }
            }
        }

        int w = getWidth();
        int h = getHeight();
        // SVG实际渲染尺寸 60x54（来自 pet.html: <svg width="60" height="54" viewBox="0 0 300 200">）
        // viewBox比例: 300:200 = 3:2
        // 我们按宽度60dp来缩放，高度自动按比例
        float density = getResources().getDisplayMetrics().density;
        float petDisplayW = 60 * density;
        float petDisplayH = 54 * density;
        float scale = petDisplayW / 300f; // viewBox 300 → 60dp
        float petW = petDisplayW;
        float petH = petDisplayH;
        // 居中偏上（底部留气泡空间）
        float offsetX = (w - petW) / 2f;
        float offsetY = h * 0.25f; // 宠物在View的25%位置，上方留气泡空间

        canvas.save();
        // 动画变换
        float cx = offsetX + petW / 2;
        float cy = offsetY + petH / 2;
        canvas.translate(cx + animTranslateX, cy + animTranslateY * scale);
        canvas.rotate(animRotation);
        canvas.scale(animScaleX, animScaleY);
        canvas.translate(-cx, -cy);

        // 颜色定义（1:1对应SVG）
        // 热度衰减：每秒降温2点
        if (heat > 0 && now - lastHeatDecayMs > 1000) {
            heat = Math.max(0, heat - 2);
            lastHeatDecayMs = now;
            bodyTint = heat >= 80 ? 1f : (heat >= 50 ? heat / 100f : 0f);
            invalidate();
        }
        // 颜色定义
        int bodyRed = 0xE6 + (int)((0xFF - 0xE6) * bodyTint);
        int bodyGreen = (int)(0x8A * (1 - bodyTint * 0.6f));
        int bodyBlue = (int)(0x65 * (1 - bodyTint * 0.5f));
        int bodyColor = 0xFF000000 | (bodyRed << 16) | (bodyGreen << 8) | bodyBlue; // 暖粉→羞红
        int footColor = 0xFFd47952;   // #d47952 深一点的四只脚
        int eyeColor = 0xFF1a1a1a;    // 黑色眼睛
        int eyeHighlight = 0xFFFFFFFF; // 白色高光
        int blushColor = 0xFFff9e8c;  // 腮红
        int bodyShineColor = 0x33FFFFFF; // 身体高光

        // 身体高光渐变
        Paint bodyShinePaint = new Paint();
        RadialGradient shine = new RadialGradient(
            offsetX + petW * 0.35f, offsetY + petH * 0.30f, petW * 0.4f,
            new int[]{0x33FFFFFF, 0x11000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP
        );
        bodyShinePaint.setShader(shine);

        // --- 耳朵（SVG: x=42,y=64,w=18,h=50, rx=9 和 x=240,y=64,w=18,h=50, rx=9）---
        Paint earPaint = new Paint(); earPaint.setColor(bodyColor); earPaint.setAntiAlias(true);
        float earW = 18 * scale, earH = 50 * scale, earRx = 9 * scale;
        canvas.drawRoundRect(offsetX + 42 * scale, offsetY + 64 * scale,
            offsetX + (42 + 18) * scale, offsetY + (64 + 50) * scale, earRx, earRx, earPaint);
        canvas.drawRoundRect(offsetX + 240 * scale, offsetY + 64 * scale,
            offsetX + (240 + 18) * scale, offsetY + (64 + 50) * scale, earRx, earRx, earPaint);

        // --- 身体主体（SVG: x=80,y=50,w=140,h=90,rx=14）---
        Paint bodyPaint = new Paint(); bodyPaint.setColor(bodyColor); bodyPaint.setAntiAlias(true);
        float bodyX = offsetX + 80 * scale, bodyY = offsetY + 50 * scale;
        float bodyW = 140 * scale, bodyH = 90 * scale, bodyRx = 14 * scale;
        canvas.drawRoundRect(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH, bodyRx, bodyRx, bodyPaint);
        // 身体高光覆盖
        canvas.drawRoundRect(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH, bodyRx, bodyRx, bodyShinePaint);

        // --- 四只脚（SVG: x=90,114,168,192 / y=140,w=18,h=28,rx=6）---
        Paint footPaint = new Paint(); footPaint.setColor(footColor); footPaint.setAntiAlias(true);
        float footW = 18 * scale, footH = 28 * scale, footRx = 6 * scale;
        for (float fx : new float[]{90, 114, 168, 192}) {
            canvas.drawRoundRect(offsetX + fx * scale, offsetY + 140 * scale,
                offsetX + (fx + 18) * scale, offsetY + (140 + 28) * scale, footRx, footRx, footPaint);
        }

        // --- 左眼（SVG: 黑底x=112,w=22 / 高光x=116,w=6 / 眼皮x=108,w=30）---
        drawEye(canvas, offsetX, offsetY, scale, 112, 72, 22, 28, 4, 116, 76, 6, 8, 108, 86, 30);
        // --- 右眼（SVG: 黑底x=166,w=22 / 高光x=170,w=6 / 眼皮x=162,w=30）---
        drawEye(canvas, offsetX, offsetY, scale, 166, 72, 22, 28, 4, 170, 76, 6, 8, 162, 86, 30);

        // --- 腮红（SVG: 左cx=100,cy=98,rx=10,ry=5 / 右cx=200,cy=98,rx=10,ry=5）---
        if (blushOn) {
            Paint blushPaint = new Paint(); blushPaint.setColor(blushColor); blushPaint.setAntiAlias(true);
            blushPaint.setAlpha(128);
            canvas.drawOval(offsetX + (100 - 10) * scale, offsetY + (98 - 5) * scale,
                offsetX + (100 + 10) * scale, offsetY + (98 + 5) * scale, blushPaint);
            canvas.drawOval(offsetX + (200 - 10) * scale, offsetY + (98 - 5) * scale,
                offsetX + (200 + 10) * scale, offsetY + (98 + 5) * scale, blushPaint);
        }

        // --- 嘴 ---
        Paint mouthPaint = new Paint(); mouthPaint.setColor(eyeColor); mouthPaint.setAntiAlias(true);
        mouthPaint.setStyle(Paint.Style.STROKE);
        mouthPaint.setStrokeWidth(2.5f * scale);
        mouthPaint.setStrokeCap(Paint.Cap.ROUND);

        if (mouthOpen) {
            // SVG: ellipse cx=168,cy=104,rx=6,ry=7
            mouthPaint.setStyle(Paint.Style.FILL);
            canvas.drawOval(offsetX + (168 - 6) * scale, offsetY + (104 - 7) * scale,
                offsetX + (168 + 6) * scale, offsetY + (104 + 7) * scale, mouthPaint);
        } else {
            // SVG: path d="M140 100 Q150 108 160 100"
            Path mouthPath = new Path();
            mouthPath.moveTo(offsetX + 140 * scale, offsetY + 100 * scale);
            mouthPath.quadTo(offsetX + 150 * scale, offsetY + 108 * scale,
                offsetX + 160 * scale, offsetY + 100 * scale);
            canvas.drawPath(mouthPath, mouthPaint);
        }

        canvas.restore();

        // --- 气泡（独立于pet变换）---
        if (!bubbleText.isEmpty()) {
            drawBubble(canvas, w, h);
        }

        // === 自言自语 ===
        if (bubbleText.isEmpty() && heat < 30 && now >= nextMurmurMs && !state.equals("sleeping")) {
            nextMurmurMs = now + 15000 + (long)(Math.random() * 30000); // 15-45秒一次
            final String[] murrs = {
                "今天天气不错呢", "好无聊啊", "想出去玩", "宝贝在干嘛呢",
                "肚子饿了", "有点困了", "好想被戳", "……", "哼",
                "好安静", "发呆中", "看看窗外", "想喝奶茶"
            };
            final String murr = murrs[(int)(Math.random() * murrs.length)];
            say(murr, "whisper");
        }
    }

    private void drawEye(Canvas canvas, float ox, float oy, float s,
                         float ebX, float ebY, float ebW, float ebH, float ebR,
                         float ehX, float ehY, float ehW, float ehH,
                         float elX, float elY, float elW) {
        // 黑色眼球
        Paint eyeBallPaint = new Paint(); eyeBallPaint.setColor(0xFF1a1a1a); eyeBallPaint.setAntiAlias(true);
        if (!eyesClosed) {
            canvas.drawRoundRect(ox + ebX * s, oy + ebY * s, ox + (ebX + ebW) * s, oy + (ebY + ebH) * s, ebR * s, ebR * s, eyeBallPaint);
            // 白色高光
            Paint hlPaint = new Paint(); hlPaint.setColor(0xFFFFFFFF); hlPaint.setAntiAlias(true);
            canvas.drawRoundRect(ox + ehX * s, oy + ehY * s, ox + (ehX + ehW) * s, oy + (ehY + ehH) * s, 3 * s, 3 * s, hlPaint);
        }
        // 眼皮（闭眼时显示）
        if (eyesClosed) {
            Paint lidPaint = new Paint(); lidPaint.setColor(0xFF1a1a1a); lidPaint.setAntiAlias(true);
            canvas.drawRoundRect(ox + elX * s, oy + elY * s, ox + (elX + elW) * s, oy + (elY + 5) * s, 2 * s, 2 * s, lidPaint);
        }
    }

    private void drawBubble(Canvas canvas, int w, int h) {
        String text = bubbleText;
        if (text.isEmpty()) return;

        // 气泡样式颜色
        int bgColor, textColor;
        switch (bubbleStyle) {
            case "pink":    bgColor = 0xEBFFC8C8; textColor = 0xFFC05050; break;
            case "whisper": bgColor = 0xD9E6E6E6; textColor = 0xFF888888; break;
            case "red":     bgColor = 0xEBFF9696; textColor = 0xFF880000; break;
            case "jealous": bgColor = 0xEBB4E6B4; textColor = 0xFF388038; break;
            default:        bgColor = 0xEBFFFFFF; textColor = 0xFF555555; break;
        }

        Paint textPaint = new Paint();
        textPaint.setColor(textColor);
        textPaint.setTextSize(10 * getResources().getDisplayMetrics().density);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.DEFAULT);

        float textW = textPaint.measureText(text);
        float density = getResources().getDisplayMetrics().density;
        float padX = 8 * density;
        float padY = 4 * density;
        float bubbleW = textW + padX * 2;
        float bubbleH = textPaint.getTextSize() + padY * 2;
        float bubbleX = (w - bubbleW) / 2;
        float bubbleY = h * 0.15f; // 在宠物上方

        // 圆角背景
        Paint bgPaint = new Paint();
        bgPaint.setColor(bgColor);
        bgPaint.setAntiAlias(true);
        bgPaint.setShadowLayer(8 * density, 0, 2 * density, 0x14000000);
        canvas.drawRoundRect(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH, 10 * density, 10 * density, bgPaint);

        // 文字
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = bubbleY + (bubbleH - fm.bottom + fm.top) / 2f - fm.top;
        canvas.drawText(text, bubbleX + padX, textY, textPaint);
    }

    // ==================== 触摸 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long now = System.currentTimeMillis();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                isDragging = false;
                isFling = false;
                dragVelocityX = 0;
                dragVelocityY = 0;
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                touchStartMs = now;

                // 唤醒沉睡的pet
                if (state.equals("sleeping")) {
                    triggerPet("wakeup");
                }

                // 长按检测
                postDelayed(() -> {
                    if (!isDragging && System.currentTimeMillis() - touchStartMs >= 450) {
                        tapCount = 0;
                        if (pendingTap != null) { removeCallbacks(pendingTap); pendingTap = null; }
                        triggerPet("longPress");
                    }
                }, 500);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                long moveDt = Math.max(1, System.currentTimeMillis() - touchStartMs);
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                    isDragging = true;
                    // 累积甩出速度（px/s）
                    if (moveDt > 30) {
                        dragVelocityX = dx * 1000f / moveDt;
                        dragVelocityY = dy * 1000f / moveDt;
                    }
                }
                if (isDragging && dragCallback != null) {
                    dragCallback.onDrag(dx, dy);
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    // 取消长按
                    removeCallbacks(null);
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(null);
                if (isDragging) {
                    // 甩出检测：速度够快则飞出再爬回
                    float speed = (float) Math.hypot(dragVelocityX, dragVelocityY);
                    if (speed > FLING_THRESHOLD) {
                        startFling(dragVelocityX, dragVelocityY);
                    } else {
                        isDragging = false;
                    }
                    return true;
                }

                // 单击/双击/连击
                if (now - lastTapMs < 400 && now - lastTapMs > 0) {
                    // 双击
                    tapCount = 0;
                    if (pendingTap != null) { removeCallbacks(pendingTap); pendingTap = null; }
                    triggerPet("doubleTap");
                    lastTapMs = 0;
                } else {
                    tapCount++;
                    lastTapMs = now;
                    if (pendingTap != null) removeCallbacks(pendingTap);
                    pendingTap = () -> {
                        if (tapCount >= 8) triggerPet("comboX8");
                        else if (tapCount >= 5) triggerPet("comboX5");
                        else if (tapCount >= 3) triggerPet("comboX3");
                        else triggerPet("tap");
                        tapCount = 0;
                        pendingTap = null;
                    };
                    postDelayed(pendingTap, 400);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void scheduleNextBlink() {
        nextBlinkMs = System.currentTimeMillis() + 2800 + (long) (Math.random() * 3000);
    }

    private float cubicOut(float t) { return 1 - (float) Math.pow(1 - t, 3); }

    // ==================== 甩出爬回 (fling) ====================

    private void startFling(float vx, float vy) {
        isFling = true;
        isDragging = false;
        state = "flingOut";
        // 沿速度方向飞出屏幕
        float mag = (float) Math.hypot(vx, vy);
        float nx = vx / (mag == 0 ? 1f : mag);
        float ny = vy / (mag == 0 ? 1f : mag);
        float dist = Math.min(400f, mag * 0.25f); // 飞出距离
        flingTargetX = nx * dist;
        flingTargetY = ny * dist;
        animStartMs = System.currentTimeMillis();
        animDurationMs = FLING_OUT_MS;
        currentAnim = "flying";
        say("咻~", "whisper");
        invalidate();
        // 飞出结束后自动爬回
        postDelayed(this::startFlingReturn, FLING_OUT_MS);
    }

    private void startFlingReturn() {
        state = "flingBack";
        animStartMs = System.currentTimeMillis();
        animDurationMs = FLING_BACK_MS;
        currentAnim = "";
        say("我回来啦~", "normal");
        invalidate();
        postDelayed(() -> {
            state = "awake";
            currentAnim = "";
            flingTargetX = 0;
            flingTargetY = 0;
            invalidate();
        }, FLING_BACK_MS);
    }

    private void applyFlingOut(float t) {
        animTranslateX = flingTargetX * t;
    }

    private void applyFlingBack(float t) {
        animTranslateX = flingTargetX * (1 - t);
    }

    // ==================== 睡眠 / 唤醒（外部调用） ====================

    /** 入睡（长时间无人理时） */
    public void goToSleep() {
        if (state.equals("sleeping")) return;
        state = "sleeping";
        triggerPet("afk_sleep");
    }

    /** 唤醒 */
    public void wake() {
        if (!state.equals("sleeping")) return;
        triggerPet("wakeup");
    }

    public boolean isAsleep() { return state.equals("sleeping"); }
}
