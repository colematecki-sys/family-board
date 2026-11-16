package com.familyboard.kiosk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * The whole kiosk: shows the board (a local web page), keeps the screen on,
 * dims it when the front camera sees no movement, and brightens on movement or touch.
 */
public class MainActivity extends Activity implements Camera.PreviewCallback {
    private static final float DIM = 0.01f;
    private static final int GRID_W = 16, GRID_H = 12;

    private final Handler ui = new Handler();
    private WebView web;
    private Camera camera;
    private SurfaceTexture texture;
    private int frameW, frameH;
    private int[] prevGrid;
    private long lastFrameAt, ignoreMotionUntil;
    private volatile boolean dimmed;

    // Tunable from the page's setup panel; calibrated on the real tablet.
    private volatile long dimAfterMs = 2 * 60 * 1000;
    private volatile int motionThreshold = 8;
    private volatile int quietStart = 22, quietEnd = 6; // hours of the day; equal = no quiet hours
    private volatile long lastActivityAt;
    private volatile int lastDiff, motionCount;
    private volatile String cameraState = "not started";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean shouldDim = System.currentTimeMillis() - lastActivityAt > dimAfterMs;
            if (shouldDim != dimmed) setDimmed(shouldDim);
            ui.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory());
        } catch (Exception e) {
            // Falls back to the platform default; the page's connection test will show the failure.
        }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Tablet");
        setContentView(web);
        web.loadUrl("file:///android_asset/board.html");
        lastActivityAt = System.currentTimeMillis();
        readLink(getIntent());
    }

    /** The PC links the tablet to the family's Google web app by launching the app with url + key extras. */
    private boolean readLink(Intent it) {
        String url = it == null ? null : it.getStringExtra("url");
        String key = it == null ? null : it.getStringExtra("key");
        // Any app can send this intent, so only accept Google Apps Script addresses.
        if (url == null || key == null || !url.startsWith("https://script.google.com/")) return false;
        getPreferences(MODE_PRIVATE).edit().putString("url", url).putString("key", key).commit();
        return true;
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        if (readLink(it)) web.evaluateJavascript("refresh()", null);
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        startCamera();
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopCamera();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override public void onBackPressed() {
        // Kiosk: the back button does nothing.
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        wake();
        return super.dispatchTouchEvent(ev);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void wake() {
        lastActivityAt = System.currentTimeMillis();
        if (dimmed) setDimmed(false);
    }

    private void setDimmed(boolean dim) {
        dimmed = dim;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Awake = whatever brightness the tablet is set to; dimmed = backlight at minimum.
        lp.screenBrightness = dim ? DIM : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        // The screen's own light changes what the camera sees; don't count that as movement.
        ignoreMotionUntil = System.currentTimeMillis() + 3000;
        // The page also goes black with a dim clock, so dimming shows even if the backlight barely changes.
        web.evaluateJavascript("onDim(" + dim + ")", null);
    }

    private void startCamera() {
        try {
            int id = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) id = i;
            }
            if (id < 0) { cameraState = "no front camera"; return; }
            camera = Camera.open(id);
            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            Camera.Size best = sizes.get(0);
            for (Camera.Size s : sizes) {
                if (s.width * s.height < best.width * best.height) best = s;
            }
            p.setPreviewSize(best.width, best.height);
            camera.setParameters(p);
            frameW = best.width;
            frameH = best.height;
            texture = new SurfaceTexture(10);
            camera.setPreviewTexture(texture);
            camera.setPreviewCallback(this);
            camera.startPreview();
            prevGrid = null;
            cameraState = "running " + frameW + "x" + frameH;
        } catch (Exception e) {
            cameraState = "error: " + e;
            stopCamera();
        }
    }

    private void stopCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    /** Compares brightness on a coarse grid twice a second; a big enough change counts as movement. */
    @Override public void onPreviewFrame(byte[] data, Camera c) {
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < 500 || data == null || data.length < frameW * frameH) return;
        lastFrameAt = now;
        int[] grid = new int[GRID_W * GRID_H];
        for (int gy = 0; gy < GRID_H; gy++) {
            for (int gx = 0; gx < GRID_W; gx++) {
                int x = (gx * 2 + 1) * frameW / (GRID_W * 2);
                int y = (gy * 2 + 1) * frameH / (GRID_H * 2);
                grid[gy * GRID_W + gx] = data[y * frameW + x] & 0xff; // first plane of NV21 is brightness
            }
        }
        if (prevGrid != null) {
            int sum = 0;
            for (int i = 0; i < grid.length; i++) sum += Math.abs(grid[i] - prevGrid[i]);
            lastDiff = sum / grid.length;
            if (lastDiff >= motionThreshold && now > ignoreMotionUntil && !inQuietHours()) {
                motionCount++;
                wake();
            }
        }
        prevGrid = grid;
    }

    /** During quiet hours movement doesn't wake the screen (a touch still does). */
    private boolean inQuietHours() {
        int s = quietStart, e = quietEnd, h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (s == e) return false;
        return s < e ? (h >= s && h < e) : (h >= s || h < e);
    }

    private volatile java.util.List<java.io.File> photos = new java.util.ArrayList<java.io.File>();

    private static void addPhotos(java.io.File dir, java.util.List<java.io.File> into, int depth) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory()) { if (depth > 0) addPhotos(f, into, depth - 1); }
            else if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) into.add(f);
        }
    }

    private void toPage(final String js) {
        ui.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript(js, null); }
        });
    }

    /** Methods the board page can call as Tablet.xxx(). */
    private class Bridge {
        /** Fetches a URL off the UI thread and calls onFetch(id, httpStatus, body) in the page; status 0 = failed. */
        @JavascriptInterface public void fetch(final String id, final String url) {
            new Thread(new Runnable() {
                @Override public void run() {
                    int code = 0;
                    String body;
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(20000);
                        code = conn.getResponseCode();
                        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        if (in != null) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0 && out.size() < 2000000) out.write(buf, 0, n);
                            in.close();
                        }
                        body = out.toString("UTF-8");
                    } catch (Exception e) {
                        code = 0;
                        body = e.toString();
                    }
                    toPage("onFetch(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")");
                }
            }).start();
        }

        /** Address the page should load tasks and events from; empty until the tablet is linked. */
        @JavascriptInterface public String dataUrl() {
            String url = getPreferences(MODE_PRIVATE).getString("url", "");
            return url.length() == 0 ? "" : url + "?key=" + getPreferences(MODE_PRIVATE).getString("key", "");
        }

        @JavascriptInterface public void configure(int dimAfterSeconds, int threshold, int quietFrom, int quietTo) {
            dimAfterMs = Math.max(10, dimAfterSeconds) * 1000L;
            motionThreshold = Math.max(1, threshold);
            quietStart = ((quietFrom % 24) + 24) % 24;
            quietEnd = ((quietTo % 24) + 24) % 24;
        }

        @JavascriptInterface public String status() {
            return "{\"camera\":" + JSONObject.quote(cameraState)
                    + ",\"dimmed\":" + dimmed
                    + ",\"quiet\":" + inQuietHours()
                    + ",\"diff\":" + lastDiff
                    + ",\"motions\":" + motionCount
                    + ",\"idleSeconds\":" + (System.currentTimeMillis() - lastActivityAt) / 1000 + "}";
        }

        /** Setup-panel test: pretend nobody has been around, so the next tick dims. */
        @JavascriptInterface public void dimNow() {
            lastActivityAt = System.currentTimeMillis() - dimAfterMs - 1000;
        }

        /** How many slideshow photos are in the tablet's FamilyBoard folder (re-scanned on each call). */
        @JavascriptInterface public int photoCount() {
            java.util.ArrayList<java.io.File> found = new java.util.ArrayList<java.io.File>();
            addPhotos(new java.io.File(android.os.Environment.getExternalStorageDirectory(), "FamilyBoard"), found, 2);
            java.util.Collections.sort(found);
            photos = found;
            return found.size();
        }

        /**
         * One photo as base64 JPEG, shrunk to roughly screen size and turned upright.
         * Phone photos are far too big for this tablet's memory at full size. Returns "" if it can't be read.
         */
        @JavascriptInterface public String photo(int index) {
            try {
                java.util.List<java.io.File> list = photos;
                if (index < 0 || index >= list.size()) return "";
                String path = list.get(index).getPath();
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(path, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path, o);
                if (bmp == null) return "";
                int turn = 0;
                int orient = new android.media.ExifInterface(path).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_90) turn = 90;
                if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_180) turn = 180;
                if (orient == android.media.ExifInterface.ORIENTATION_ROTATE_270) turn = 270;
                if (turn != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(turn);
                    bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
                bmp.recycle();
                return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable t) { // includes running out of memory on a huge image
                return "";
            }
        }

        /** The way out of the kiosk: opens Android's Settings. */
        @JavascriptInterface public void openSettings() {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
