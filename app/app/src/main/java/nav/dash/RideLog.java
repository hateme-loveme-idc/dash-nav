package nav.dash;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ride diagnostics the rider can find and share: Download/Dash Nav/ride-YYYYMMDD-HHMM.txt (joystick codes as the
 * dash sends them, frame rate decisions, link events, caller data). No location or contact data is written.
 */
final class RideLog {
    private RideLog() {}
    private static OutputStream out;
    private static Uri uri;
    private static ContentResolver cr;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static synchronized void start(Context c) {
        stop();
        try {
            cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, "ride-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dash Nav");
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            out = uri == null ? null : cr.openOutputStream(uri, "wa");
            add("Dash Nav " + BuildConfig.VERSION_NAME + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE);
        } catch (Exception e) { Log.w(DashService.TAG, "ride log", e); out = null; }
    }

    static synchronized void add(String line) {
        Log.i(DashService.TAG, line);
        if (out == null) return;
        try { out.write((TS.format(new Date()) + "  " + line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (Exception ignored) {}
    }

    static synchronized void stop() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        out = null; uri = null;
    }
}

/** The dash stream as a normal MP4 in Movies/Dash Nav (visible in the Gallery). */
final class RideVideo {
    private final ContentResolver cr; private Uri uri; private ParcelFileDescriptor fd;
    private MediaMuxer mux; private int track = -1; private boolean started; private long frames;

    RideVideo(Context c) {
        cr = c.getContentResolver();
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, "ride-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Dash Nav");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            fd = cr.openFileDescriptor(uri, "rw");
            mux = new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception e) { Log.w(DashService.TAG, "ride video", e); mux = null; }
    }

    synchronized void format(MediaFormat f) {
        if (mux == null || started) return;
        try { track = mux.addTrack(f); mux.start(); started = true; } catch (Exception e) { Log.w(DashService.TAG, "mux start", e); }
    }

    synchronized void frame(byte[] data, MediaCodec.BufferInfo info) {
        if (!started || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, data.length, info.presentationTimeUs, info.flags);
        try { mux.writeSampleData(track, ByteBuffer.wrap(data), bi); frames++; } catch (Exception ignored) {}
    }

    synchronized void finish() {
        try { if (started) mux.stop(); } catch (Exception ignored) {}
        try { if (mux != null) mux.release(); } catch (Exception ignored) {}
        try { if (fd != null) fd.close(); } catch (Exception ignored) {}
        mux = null; started = false;
        try {
            if (uri == null) return;
            if (frames == 0) { cr.delete(uri, null, null); return; }
            ContentValues v = new ContentValues(); v.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(uri, v, null, null);
            RideLog.add("ride video saved: Movies/Dash Nav (" + frames + " frames)");
        } catch (Exception e) { Log.w(DashService.TAG, "ride video finish", e); }
    }
}
