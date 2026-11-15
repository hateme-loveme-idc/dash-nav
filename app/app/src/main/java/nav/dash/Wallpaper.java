package nav.dash;

import android.graphics.*;

/** The dash wallpaper: fitted to the dash's 526x300 picture area, and a preview on a dash replica. */
final class Wallpaper {
    private Wallpaper() {}

    /** Centre-crop to 526:300 and scale to 526x300. */
    static Bitmap fit(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        float target = 526f / 300f;
        int cw = w, ch = h;
        if ((float) w / h > target) cw = Math.round(h * target); else ch = Math.round(w / target);
        Rect from = new Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch);
        Bitmap out = Bitmap.createBitmap(Overlay.W, Overlay.H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, Overlay.W, Overlay.H), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /**
     * How it looks on the Himalayan 450's round TFT: the picture fills the dome above the dash's gold band
     * (top corners clipped by the circle); the band and everything below belong to the dash.
     */
    static Bitmap preview(Bitmap wp) { return preview(wp, "No wallpaper: the Maps map shows here"); }

    static Bitmap preview(Bitmap wp, String empty) {
        int S = 600, cx = S / 2, R = 280;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2A2A2E); c.drawCircle(cx, cx, R + 14, p);
        p.setColor(Color.BLACK); c.drawCircle(cx, cx, R, p);
        c.save();
        Path clip = new Path(); clip.addCircle(cx, cx, R, Path.Direction.CW); c.clipPath(clip);
        int vw = 2 * R, vh = Math.round(vw * 300f / 526f), top = cx - R;   // stream = full dial width, from its top
        Rect dome = new Rect(cx - vw / 2, top, cx + vw / 2, top + vh);
        if (wp != null) c.drawBitmap(wp, null, dome, new Paint(Paint.FILTER_BITMAP_FLAG));
        else { p.setColor(0xFF555555); p.setTextSize(24); p.setTextAlign(Paint.Align.CENTER); c.drawText(empty, cx, top + vh / 2f, p); }
        int band = top + vh;
        // the dash's own band + instruments, as on the Himalayan 450 (they're the dash's, not changed by us)
        int gold = 0xFF8F8550, ink = 0xFFFAFAF5;
        p.setColor(gold); c.drawRect(0, band, S, band + 44, p);
        p.setColor(ink); p.setTextAlign(Paint.Align.RIGHT); p.setTextSize(28); c.drawText("24.8 km", cx + 200, band + 32, p);
        p.setColor(ink); c.drawRect(cx + 50, band + 14, cx + 80, band + 32, p);
        p.setTextAlign(Paint.Align.LEFT); p.setTextSize(18); c.drawText("ODO", cx - 200, band + 82, p);
        p.setTextSize(28); c.drawText("12345 km", cx - 150, band + 86, p);
        Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG); cap.setStyle(Paint.Style.STROKE); cap.setStrokeWidth(4); cap.setColor(gold);
        RectF capR = new RectF(cx + 10, band + 52, cx + 220, band + 120); c.drawRoundRect(capR, 34, 34, cap);
        p.setTextSize(44); p.setColor(0xFF3CC850); c.drawText("N", cx + 30, band + 102, p);
        p.setColor(ink); p.setTextSize(48); c.drawText("0", cx + 140, band + 104, p);
        p.setTextSize(14); c.drawText("km/h", cx + 172, band + 104, p);
        p.setColor(0xFF777777); p.setTextSize(16); p.setTextAlign(Paint.Align.CENTER); c.drawText("the dash's own band and instruments", cx, band + 160, p);
        c.restore();
        return b;
    }
}
