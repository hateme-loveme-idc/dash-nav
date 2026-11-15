package nav.dash;

import android.graphics.*;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Option B: Google-style turn card at the top centre, ETA chip bottom-left (time left · km left · arrival). */
final class Overlay {
    static final int W = 526, H = 300;
    private final Paint card = fill(0xF00F5F46), chip = fill(0xE60B1014), white = fill(Color.WHITE), mint = fill(0xFF7EE0B5);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD), med = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    Overlay() {
        stroke.setColor(Color.WHITE); stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
    }
    /**
     * The Tripper Dash shows the 526x300 stream across the full width of its round TFT, top edge at the top of
     * the circle, bottom edge on the dash's gold band (as in the official app's photo). So the visible part is a
     * circle of radius W/2 centred W/2 below the top edge: half the visible width at height y.
     */
    static float halfChord(float y) {
        float r = W / 2f, d = r - y;
        return d >= r ? 0 : (float) Math.sqrt(r * r - d * d);
    }

    static Paint fill(int c) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(c); return p; }

    void draw(Canvas c, NavState.Nav s) {
        if (!s.active()) return;
        // ---- turn card ----
        String what = NavText.clean(s.instruction);          // "Turn right onto X" without the distance/asides
        String big = !s.turnDist.isEmpty() ? s.turnDist : shorten(what, 20);
        String small = !s.turnDist.isEmpty() ? (s.street.isEmpty() ? shorten(what, 28) : (s.kind.equals("straight") && !what.toLowerCase(Locale.ROOT).contains("onto") ? "" : "onto ") + s.street)
                : (s.street.isEmpty() || what.endsWith(s.street) && what.length() < 20 ? "" : shorten(s.street, 28));
        text(white, bold, 26); float wBig = white.measureText(big);
        text(white, med, 13); float wSmall = white.measureText(small);
        float cardY = 46;      // the round dial is narrow at the top: card sits where it fits inside the circle
        float maxText = 2 * halfChord(cardY + 6) - 10 - (10 + 38 + 10 + 16);
        while (wBig > maxText && big.length() > 6) { big = big.substring(0, big.length() - 2) + "…"; text(white, bold, 26); wBig = white.measureText(big); }
        while (wSmall > maxText && small.length() > 6) { small = small.substring(0, small.length() - 2) + "…"; text(white, med, 13); wSmall = white.measureText(small); }
        float w = 10 + 38 + 10 + Math.max(wBig, wSmall) + 16, h = 52, x = (W - w) / 2f, y = cardY;
        c.drawRoundRect(new RectF(x, y, x + w, y + h), 16, 16, card);
        if (s.icon != null) c.drawBitmap(s.icon, null, new RectF(x + 10, y + 7, x + 48, y + 45), icon);
        else arrow(c, s.kind, x + 10, y + 7, 38);
        text(white, bold, 26); c.drawText(big, x + 58, y + (small.isEmpty() ? 35 : 27), white);
        if (!small.isEmpty()) { text(white, med, 13); c.drawText(small, x + 58, y + 44, white); }

        // ---- ETA chip ----
        String mins = minutesLeft(s.arrive);
        String km = s.leftM >= 0 ? String.format(Locale.US, s.leftM >= 10_000 ? "%.0f km" : "%.1f km", s.leftM / 1000.0) : "";
        text(mint, bold, 21); text(white, bold, 14);
        float cx = 30, cy = H - 12 - 34, pad = 10, gap = 10;
        float cw = pad + mint.measureText(mins) + (km.isEmpty() ? 0 : gap + white.measureText(km)) + gap + white.measureText(s.arrive) + pad;
        c.drawRoundRect(new RectF(cx, cy, cx + cw, cy + 34), 12, 12, chip);
        float tx = cx + pad;
        c.drawText(mins, tx, cy + 24, mint); tx += mint.measureText(mins) + gap;
        if (!km.isEmpty()) { c.drawText(km, tx, cy + 23, white); tx += white.measureText(km) + gap; }
        white.setAlpha(190); c.drawText(s.arrive, tx, cy + 23, white); white.setAlpha(255);
    }

    private void text(Paint p, Typeface t, float size) { p.setTypeface(t); p.setTextSize(size); }
    static String shorten(String t, int n) { return t.length() <= n ? t : t.substring(0, n - 1) + "…"; }

    static String minutesLeft(String arrive) { return NavText.minutesLeft(arrive, System.currentTimeMillis()); }

    void arrow(Canvas c, String kind, float x, float y, float s) {
        stroke.setStrokeWidth(s * 0.15f);
        Path p = new Path(); float k = s / 48f;
        switch (kind) {
            case "right": case "slight_right": case "sharp_right": case "fork_right": case "ramp_right":
                p.moveTo(14 * k, 40 * k); p.lineTo(14 * k, 22 * k); p.quadTo(14 * k, 16 * k, 20 * k, 16 * k); p.lineTo(36 * k, 16 * k);
                p.moveTo(30 * k, 8 * k); p.lineTo(38 * k, 16 * k); p.lineTo(30 * k, 24 * k); break;
            case "left": case "slight_left": case "sharp_left": case "fork_left": case "ramp_left":
                p.moveTo(34 * k, 40 * k); p.lineTo(34 * k, 22 * k); p.quadTo(34 * k, 16 * k, 28 * k, 16 * k); p.lineTo(12 * k, 16 * k);
                p.moveTo(18 * k, 8 * k); p.lineTo(10 * k, 16 * k); p.lineTo(18 * k, 24 * k); break;
            case "uturn":
                p.moveTo(30 * k, 40 * k); p.lineTo(30 * k, 18 * k); p.quadTo(30 * k, 8 * k, 21 * k, 8 * k); p.quadTo(12 * k, 8 * k, 12 * k, 18 * k); p.lineTo(12 * k, 34 * k);
                p.moveTo(6 * k, 28 * k); p.lineTo(12 * k, 36 * k); p.lineTo(18 * k, 28 * k); break;
            default:
                p.moveTo(24 * k, 42 * k); p.lineTo(24 * k, 8 * k); p.moveTo(14 * k, 18 * k); p.lineTo(24 * k, 8 * k); p.lineTo(34 * k, 18 * k);
        }
        c.save(); c.translate(x, y); c.drawPath(p, stroke); c.restore();
    }

    // ---- on-dash UI: joystick menu, short confirmations, "press again" hint ----
    private final Paint menuBg = fill(0xF20B1014), menuSel = fill(0xFF0F5F46), hintBg = fill(0xE60B1014);

    void drawUi(Canvas c, String[] menu, int sel, String badge, String hint) {
        if (menu != null) {
            float y = 52, w = Math.min(290, 2 * halfChord(y + 6) - 10), rowH = 27, h = 32 + rowH * menu.length + 20, x = (W - w) / 2f;
            c.drawRoundRect(new RectF(x, y, x + w, y + h), 16, 16, menuBg);
            text(mint, bold, 13); c.drawText("Dash Nav", x + 16, y + 22, mint);
            for (int i = 0; i < menu.length; i++) {
                float ry = y + 32 + i * rowH;
                if (i == sel) c.drawRoundRect(new RectF(x + 8, ry, x + w - 8, ry + rowH - 4), 10, 10, menuSel);
                text(white, i == sel ? bold : med, 15); c.drawText(menu[i], x + 18, ry + 19, white);
            }
            text(white, med, 11); c.drawText("▼ next   ▶ select   ◀ close", x + 16, y + h - 9, white);
        }
        String msg = badge != null ? badge : hint;
        if (msg != null && menu == null) {
            text(white, bold, 15); float tw = white.measureText(msg) + 28, x = (W - tw) / 2f, y = H - 92;
            c.drawRoundRect(new RectF(x, y, x + tw, y + 30), 15, 15, hintBg);
            c.drawText(msg, x + 14, y + 20, white);
        }
    }

    // ---- idle screen (Google Maps not navigating) and the call banner ----
    private final Paint idleBg = fill(0xFF0B1014), accent = fill(0xFF3FB6A8), dim = fill(0xFF98A3AD), callBg = fill(0xF20B1014);

    /** Idle: the rider's wallpaper, or a calm clock screen telling them how to start. */
    void drawIdle(Canvas c, Bitmap wallpaper, String clock, String hint) {
        Rect dst = new Rect(0, 0, W, H);
        if (wallpaper != null) { c.drawBitmap(wallpaper, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG)); return; }
        c.drawRect(dst, idleBg);
        text(white, bold, 26); white.setTextAlign(Paint.Align.CENTER);
        c.drawText("Dash Nav", W / 2f, 92, white);
        text(white, bold, 64); c.drawText(clock, W / 2f, 176, white);
        text(dim, med, 15); dim.setTextAlign(Paint.Align.CENTER); c.drawText(hint, W / 2f, 222, dim);
        white.setTextAlign(Paint.Align.LEFT); dim.setTextAlign(Paint.Align.LEFT);
    }

    /** Call banner across the map (the dash's own band shows the caller too). */
    void drawCall(Canvas c, String who, String label) {
        float w = Math.min(W - 40, 2 * halfChord(150) - 30), h = 74, x = (W - w) / 2f, y = 112;
        c.drawRoundRect(new RectF(x, y, x + w, y + h), 18, 18, callBg);
        c.drawRoundRect(new RectF(x, y, x + 8, y + h), 4, 4, mint);
        text(mint, bold, 14); c.drawText(label, x + 24, y + 26, mint);
        text(white, bold, 24);
        String n = who; while (white.measureText(n) > w - 40 && n.length() > 4) n = n.substring(0, n.length() - 2) + "…";
        c.drawText(n, x + 24, y + 58, white);
    }
}
