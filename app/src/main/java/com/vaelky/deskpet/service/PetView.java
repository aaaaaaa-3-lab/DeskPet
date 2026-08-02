package com.vaelky.deskpet.service;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Custom View: orange square pet with expressions, animations, and speech bubble.
 * Fully native rendering, no WebView / JavaScript dependency.
 */
public class PetView extends View {

    private static final int PET_SIZE = 80; // dp, scaled later
    private static final float CORNER_RADIUS = 18f; // dp

    private final Paint bgPaint;
    private final Paint eyePaint;
    private final Paint mouthPaint;
    private final Paint blushPaint;
    private final Paint textPaint;
    private final Paint bubblePaint;
    private final Paint bubbleBorderPaint;

    // Pet state
    private String expression = "normal"; // normal, blush, angry, dizzy, happy, sad
    private String currentBubble = "";
    private String bubbleStyle = "normal"; // normal, jealous, whisper, pink, red
    private long bubbleShowTime = 0L;
    private static final long BUBBLE_DURATION = 3000L;

    // Animation
    private float bounceOffset = 0f;
    private float shakeOffset = 0f;
    private float eyeScale = 1f;
    private int tickCount = 0;

    private final float density;
    private final int petPx;
    private final float cornerPx;
    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private boolean animating = false;

    public PetView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        petPx = (int) (PET_SIZE * density);
        cornerPx = CORNER_RADIUS * density;

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.rgb(255, 140, 50)); // orange

        eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyePaint.setColor(Color.WHITE);
        eyePaint.setStyle(Paint.Style.FILL);

        mouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mouthPaint.setColor(Color.WHITE);
        mouthPaint.setStyle(Paint.Style.STROKE);
        mouthPaint.setStrokeWidth(2.5f * density);

        blushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blushPaint.setColor(Color.argb(100, 255, 100, 100));
        blushPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.rgb(50, 50, 50));
        textPaint.setTextSize(13 * density);
        textPaint.setFakeBoldText(true);

        bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubblePaint.setColor(Color.argb(230, 255, 255, 255));
        bubblePaint.setStyle(Paint.Style.FILL);

        bubbleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubbleBorderPaint.setColor(Color.argb(100, 0, 0, 0));
        bubbleBorderPaint.setStyle(Paint.Style.STROKE);
        bubbleBorderPaint.setStrokeWidth(1.5f * density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The view takes full space of the overlay; pet draws in the center-bottom
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Pet position: centered horizontally, bottom-aligned
        float petLeft = (w - petPx) / 2f + shakeOffset;
        float petTop = h - petPx - (5 * density) + bounceOffset;
        RectF petRect = new RectF(petLeft, petTop, petLeft + petPx, petTop + petPx);

        // Shadow
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.argb(40, 0, 0, 0));
        canvas.drawRoundRect(
            petLeft + 3 * density, petTop + 3 * density,
            petLeft + petPx + 3 * density, petTop + petPx + 3 * density,
            cornerPx, cornerPx, shadowPaint
        );

        // Body
        bgPaint.setColor(getBodyColor());
        canvas.drawRoundRect(petRect, cornerPx, cornerPx, bgPaint);

        // Eyes
        float eyeY = petTop + petPx * 0.38f;
        float eyeSpacing = petPx * 0.22f;
        float eyeRadius = petPx * 0.11f * eyeScale;

        canvas.drawCircle(petLeft + petPx * 0.35f, eyeY, eyeRadius, eyePaint);
        canvas.drawCircle(petLeft + petPx * 0.65f, eyeY, eyeRadius, eyePaint);

        // Pupils (dark)
        Paint pupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pupilPaint.setColor(Color.rgb(40, 40, 40));
        float pupilR = eyeRadius * 0.55f;
        canvas.drawCircle(petLeft + petPx * 0.35f, eyeY, pupilR, pupilPaint);
        canvas.drawCircle(petLeft + petPx * 0.65f, eyeY, pupilR, pupilPaint);

        // Eye highlights
        Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setColor(Color.WHITE);
        float highlightR = pupilR * 0.35f;
        canvas.drawCircle(petLeft + petPx * 0.35f - pupilR * 0.3f, eyeY - pupilR * 0.3f, highlightR, highlightPaint);
        canvas.drawCircle(petLeft + petPx * 0.65f - pupilR * 0.3f, eyeY - pupilR * 0.3f, highlightR, highlightPaint);

        // Mouth
        float mouthY = petTop + petPx * 0.58f;
        float mouthCenterX = petLeft + petPx * 0.5f;
        drawMouth(canvas, mouthCenterX, mouthY);

        // Blush
        if (expression.equals("blush") || expression.equals("angry")) {
            float blushY = petTop + petPx * 0.48f;
            float blushR = petPx * 0.12f;
            canvas.drawCircle(petLeft + petPx * 0.2f, blushY, blushR, blushPaint);
            canvas.drawCircle(petLeft + petPx * 0.8f, blushY, blushR, blushPaint);
        }

        // Speech bubble
        if (!currentBubble.isEmpty() && System.currentTimeMillis() - bubbleShowTime < BUBBLE_DURATION) {
            drawBubble(canvas, petLeft + petPx / 2f, petTop - 8 * density);
        }
    }

    private int getBodyColor() {
        switch (expression) {
            case "blush": return Color.rgb(255, 150, 100);
            case "angry": return Color.rgb(255, 80, 60);
            case "dizzy": return Color.rgb(255, 200, 100);
            case "happy": return Color.rgb(255, 160, 60);
            case "sad":   return Color.rgb(200, 140, 100);
            default:      return Color.rgb(255, 140, 50);
        }
    }

    private void drawMouth(Canvas canvas, float cx, float by) {
        float mouthW = petPx * 0.18f;
        float mouthH = petPx * 0.08f;

        switch (expression) {
            case "happy":
                // Smile arc
                Path smile = new Path();
                smile.addArc(cx - mouthW, by - mouthH, cx + mouthW, by + mouthH, 0, 180);
                canvas.drawPath(smile, mouthPaint);
                break;
            case "sad":
                Path frown = new Path();
                frown.addArc(cx - mouthW, by - mouthH * 2, cx + mouthW, by, 180, 180);
                canvas.drawPath(frown, mouthPaint);
                break;
            case "angry":
                // Zigzag mouth
                Path angry = new Path();
                angry.moveTo(cx - mouthW, by + mouthH * 0.3f);
                angry.lineTo(cx - mouthW * 0.5f, by - mouthH * 0.3f);
                angry.lineTo(cx, by + mouthH * 0.3f);
                angry.lineTo(cx + mouthW * 0.5f, by - mouthH * 0.3f);
                angry.lineTo(cx + mouthW, by + mouthH * 0.3f);
                canvas.drawPath(angry, mouthPaint);
                break;
            case "dizzy":
                // Open circle mouth
                canvas.drawCircle(cx, by, mouthH * 1.2f, mouthPaint);
                break;
            case "blush":
                // Small wavy
                Path blush = new Path();
                blush.moveTo(cx - mouthW * 0.8f, by);
                blush.quadTo(cx - mouthW * 0.4f, by - mouthH * 1.5f, cx, by);
                blush.quadTo(cx + mouthW * 0.4f, by - mouthH * 1.5f, cx + mouthW * 0.8f, by);
                canvas.drawPath(blush, mouthPaint);
                break;
            default:
                // Neutral: short line
                canvas.drawLine(cx - mouthW * 0.6f, by, cx + mouthW * 0.6f, by, mouthPaint);
        }
    }

    private void drawBubble(Canvas canvas, float anchorX, float anchorY) {
        int color;
        switch (bubbleStyle) {
            case "jealous": color = Color.argb(230, 255, 220, 220); break;
            case "pink":    color = Color.argb(230, 255, 200, 220); break;
            case "red":     color = Color.argb(230, 255, 180, 180); break;
            case "whisper": color = Color.argb(200, 240, 240, 245); break;
            default:        color = Color.argb(230, 255, 255, 255);
        }

        float textWidth = textPaint.measureText(currentBubble);
        float bubbleW = Math.max(textWidth + 24 * density, 60 * density);
        float bubbleH = 32 * density;
        float bubbleLeft = anchorX - bubbleW / 2f;
        float bubbleTop = anchorY - bubbleH;

        // Clamp to screen edges
        if (bubbleLeft < 4 * density) bubbleLeft = 4 * density;
        if (bubbleLeft + bubbleW > getWidth() - 4 * density) bubbleLeft = getWidth() - bubbleW - 4 * density;

        RectF bubbleRect = new RectF(bubbleLeft, bubbleTop, bubbleLeft + bubbleW, bubbleTop + bubbleH);

        bubblePaint.setColor(color);
        canvas.drawRoundRect(bubbleRect, 14 * density, 14 * density, bubblePaint);
        canvas.drawRoundRect(bubbleRect, 14 * density, 14 * density, bubbleBorderPaint);

        // Triangle pointer
        Path pointer = new Path();
        pointer.moveTo(anchorX - 6 * density, bubbleTop + bubbleH - 2 * density);
        pointer.lineTo(anchorX, bubbleTop + bubbleH + 8 * density);
        pointer.lineTo(anchorX + 6 * density, bubbleTop + bubbleH - 2 * density);
        pointer.close();
        canvas.drawPath(pointer, bubblePaint);
        canvas.drawPath(pointer, bubbleBorderPaint);

        // Text
        float textX = bubbleLeft + (bubbleW - textWidth) / 2f;
        float textY = bubbleTop + bubbleH / 2f + textPaint.getTextSize() / 3f;
        canvas.drawText(currentBubble, textX, textY, textPaint);
    }

    // Public API

    public void say(String text, String style) {
        currentBubble = text;
        bubbleStyle = style;
        bubbleShowTime = System.currentTimeMillis();
        // Schedule bubble clear
        animHandler.removeCallbacks(bubbleClearRunnable);
        animHandler.postDelayed(bubbleClearRunnable, BUBBLE_DURATION);
        invalidate();
    }

    private final Runnable bubbleClearRunnable = new Runnable() {
        @Override
        public void run() {
            currentBubble = "";
            invalidate();
        }
    };

    public void trigger(String event) {
        switch (event) {
            case "tap":
                expression = "normal";
                bounceOffset = -8 * density;
                animHandler.postDelayed(() -> { bounceOffset = 0; invalidate(); }, 150);
                break;
            case "doubleTap":
                expression = "blush";
                bounceOffset = -12 * density;
                animHandler.postDelayed(() -> { bounceOffset = 0; invalidate(); }, 200);
                break;
            case "comboX3":
                expression = "dizzy";
                shakeOffset = 10 * density;
                animHandler.postDelayed(() -> { shakeOffset = 0; invalidate(); }, 200);
                break;
            case "comboX5":
                expression = "angry";
                shakeOffset = 15 * density;
                animHandler.postDelayed(() -> { shakeOffset = 0; invalidate(); }, 250);
                break;
            case "comboX8":
                expression = "angry";
                eyeScale = 0.3f;
                shakeOffset = 20 * density;
                animHandler.postDelayed(() -> { shakeOffset = 0; eyeScale = 1f; invalidate(); }, 300);
                break;
            case "longPress":
                expression = "happy";
                bounceOffset = -6 * density;
                animHandler.postDelayed(() -> { bounceOffset = 0; invalidate(); }, 250);
                break;
            case "app_trigger":
                // slight bounce, no expression change
                bounceOffset = -4 * density;
                animHandler.postDelayed(() -> { bounceOffset = 0; invalidate(); }, 100);
                break;
            case "screenshot":
                expression = "angry";
                bounceOffset = -10 * density;
                animHandler.postDelayed(() -> { bounceOffset = 0; expression = "normal"; invalidate(); }, 300);
                break;
        }
        invalidate();
        // Reset to normal after animation
        final String prevExpr = expression;
        if (!prevExpr.equals("normal") && !event.equals("screenshot")) {
            animHandler.postDelayed(() -> {
                if (expression.equals(prevExpr)) {
                    expression = "normal";
                    invalidate();
                }
            }, 2000);
        }
    }

    public void updateMood(double heat, double pressure, double possess, String cycle, String event) {
        // Mood from Supabase affects subtle visual cues - for now just color warmth
        if (heat > 0.7) {
            bgPaint.setColor(Color.rgb(255, 100, 40));
        } else if (heat < 0.3) {
            bgPaint.setColor(Color.rgb(220, 160, 100));
        } else {
            bgPaint.setColor(Color.rgb(255, 140, 50));
        }
        invalidate();
    }

    public void showStartupBubble() {
        say("我在这儿~", "normal");
    }
}
