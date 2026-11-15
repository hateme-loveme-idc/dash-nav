package nav.dash;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Wallpaper crop editor: drag to move, pinch to zoom the picture inside an exact outline of what the dash shows
 * (526x300 picture area, its top clipped by the round dial, the dash's gold band underneath). Save = exactly
 * what's inside the outline.
 */
public class WallpaperEditor extends Activity {
    private CropView crop;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri uri = getIntent().getData();
        Bitmap img;
        try {
            img = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), uri), (dec, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight(), k = Math.max(1, Math.min(w, h) / 1600);
                dec.setTargetSize(w / k, h / k); dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (Exception e) { Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_LONG).show(); finish(); return; }

        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setBackgroundColor(MainActivity.BG);
        col.setFitsSystemWindows(true);
        TextView t = new TextView(this); t.setText("Drag and pinch: what's inside the outline is what the dash shows");
        t.setTextColor(MainActivity.MUTED); t.setTextSize(14); t.setPadding(dp(20), dp(18), dp(20), dp(8));
        col.addView(t);
        crop = new CropView(this, img);
        col.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(16), dp(8), dp(16), dp(16));
        Button cancel = button("Cancel", MainActivity.CARD, MainActivity.INK); cancel.setOnClickListener(v -> finish());
        Button save = button("Use this", MainActivity.ACCENT, 0xFF04140F); save.setOnClickListener(v -> save());
        row.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(52), 1f); sp.leftMargin = dp(12);
        row.addView(save, sp);
        col.addView(row);
        setContentView(col);
    }

    private void save() {
        try {
            Bitmap out = crop.render();
            try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "wallpaper.png"))) { out.compress(Bitmap.CompressFormat.PNG, 100, o); }
            new Settings(this).wallpaper(true);
            setResult(RESULT_OK); finish();
        } catch (Exception e) { Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(fg); b.setTextSize(16);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(bg); g.setCornerRadius(dp(26)); b.setBackground(g);
        return b;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** The picture under a fixed dash-shaped window. */
    static final class CropView extends View {
        private final Bitmap img;
        private final Matrix m = new Matrix();
        private final RectF frame = new RectF();
        private final ScaleGestureDetector scale;
        private float lastX, lastY; private boolean ready;
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), band = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        CropView(Context c, Bitmap img) {
            super(c); this.img = img;
            shade.setColor(0xC80B1014);
            line.setColor(0xFFE9EEF2); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3);
            band.setColor(0xFFB08C3C); txt.setColor(Color.WHITE); txt.setTextAlign(Paint.Align.RIGHT);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) { m.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY()); clamp(); invalidate(); return true; }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float fw = w - 48f, fh = fw * Overlay.H / Overlay.W;
            float top = Math.max(24, (h - fh - 60) / 2f);
            frame.set(24, top, 24 + fw, top + fh);
            if (!ready) {   // start: picture covers the frame, centred
                float s = Math.max(frame.width() / img.getWidth(), frame.height() / img.getHeight());
                m.setScale(s, s);
                m.postTranslate(frame.centerX() - img.getWidth() * s / 2f, frame.centerY() - img.getHeight() * s / 2f);
                ready = true;
            }
        }

        /** Never let the picture leave a gap inside the frame. */
        private void clamp() {
            RectF r = new RectF(0, 0, img.getWidth(), img.getHeight()); m.mapRect(r);
            float minS = Math.max(frame.width() / img.getWidth(), frame.height() / img.getHeight());
            float cur = r.width() / img.getWidth();
            if (cur < minS) { m.postScale(minS / cur, minS / cur, frame.centerX(), frame.centerY()); r.set(0, 0, img.getWidth(), img.getHeight()); m.mapRect(r); }
            float dx = 0, dy = 0;
            if (r.left > frame.left) dx = frame.left - r.left; else if (r.right < frame.right) dx = frame.right - r.right;
            if (r.top > frame.top) dy = frame.top - r.top; else if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom;
            m.postTranslate(dx, dy);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !scale.isInProgress()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = e.getX(); lastY = e.getY(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { m.postTranslate(e.getX() - lastX, e.getY() - lastY); lastX = e.getX(); lastY = e.getY(); clamp(); invalidate(); }
            } else if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) { int i = e.getActionIndex() == 0 ? 1 : 0; lastX = e.getX(i); lastY = e.getY(i); }
            return true;
        }

        /** The dash's visible area: the frame, its top clipped by the dial (circle of radius width/2). */
        private Path dome() {
            Path p = new Path(); float r = frame.width() / 2f;
            p.addCircle(frame.centerX(), frame.top + r, r, Path.Direction.CW);
            Path rect = new Path(); rect.addRect(frame, Path.Direction.CW);
            p.op(rect, Path.Op.INTERSECT);
            return p;
        }

        @Override protected void onDraw(Canvas c) {
            c.drawBitmap(img, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            Path d = dome();
            Path outside = new Path(); outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW); outside.op(d, Path.Op.DIFFERENCE);
            c.drawPath(outside, shade);
            c.drawPath(d, line);
            float bh = frame.width() * 0.09f;                         // the dash's gold band, for context
            c.drawRect(frame.left, frame.bottom, frame.right, frame.bottom + bh, band);
            txt.setTextSize(bh * 0.6f); c.drawText("24.8 km", frame.right - 16, frame.bottom + bh * 0.72f, txt);
        }

        /** Exactly the frame's content at the dash's 526x300. */
        Bitmap render() {
            Bitmap out = Bitmap.createBitmap(Overlay.W, Overlay.H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Matrix toOut = new Matrix(m);
            toOut.postTranslate(-frame.left, -frame.top);
            float s = Overlay.W / frame.width(); toOut.postScale(s, s);
            c.drawBitmap(img, toOut, new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        }
    }
}
