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

    // === 触摸（单击/双击/长按/连击） ===
    private int tapCount = 0;
    private long lastTapMs = 0;
    private long touchStartMs = 0;
    private float touchStartX = 0, touchStartY = 0;
    private boolean isDragging = false;
    private Runnable pendingTap = null;
    private Runnable pendingLongPress = null;
    private DragCallback dragCallback;

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
    }

    // ==================== 外部调用（替代pet.trigger/say） ====================

    public void triggerPet(String event) {
        long now = System.currentTimeMillis();
        animStartMs = now;
        animDurationMs = 2200;
        blushOn = false;
        mouthOpen = false;

        switch (event) {
            case "tap":
                currentAnim = "bouncing";
                say("诶嘿", "normal");
                break;
            case "doubleTap":
                currentAnim = "wiggling";
                say("呀~", "pink");
                blushOn = true;
                break;
            case "longPress":
                currentAnim = "squishing";
                say("好痒...", "whisper");
                blushOn = true;
                break;
            case "comboX3":
                currentAnim = "wiggling";
                say("别戳啦", "pink");
                blushOn = true;
                break;
            case "comboX5":
                currentAnim = "squishing";
                say("呜哇！！", "red");
                blushOn = true;
                mouthOpen = true;
                break;
            case "comboX8":
                currentAnim = "bouncing";
                say("要生气了！！", "red");
                blushOn = true;
                mouthOpen = true;
                break;
            case "screenshot":
                currentAnim = "squishing";
                say("偷拍我？", "jealous");
                blushOn = true;
                break;
            case "charging":
                currentAnim = "bouncing";
                say("吃饱饱~", "normal");
                break;
            default:
                currentAnim = "bouncing";
                say("诶嘿", "normal");
                break;
        }
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

        if (!currentAnim.isEmpty() && t < 1f) {
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
        // SVG viewBox: 0 0 300 200，视口尺寸60x54（CSS里svg width=60 height=54）
        // 缩放使宠物在View中心偏下
        float scale = Math.min(w / 70f, h / 64f); // 留气泡空间
        float petW = 300 * scale;
        float petH = 200 * scale;
        float offsetX = (w - petW) / 2f;
        float offsetY = h - petH - 4 * scale; // 底部留脚的空间

        canvas.save();
        // 动画变换
        float cx = offsetX + petW / 2;
        float cy = offsetY + petH / 2;
        canvas.translate(cx, cy + animTranslateY * scale);
        canvas.rotate(animRotation);
        canvas.scale(animScaleX, animScaleY);
        canvas.translate(-cx, -cy);

        // 颜色定义（1:1对应SVG）
        int bodyColor = 0xFFe68a65;   // #e68a65 暖粉色身体
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
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                touchStartMs = now;

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
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                    isDragging = true;
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
                if (isDragging) { isDragging = false; return true; }

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
}
