package com.dhanytv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String OFFLINE_BASE = "http://offline.local/";

    // Alamat server bawaan diatur dari panel admin > Pembuat APK (res/values/strings.xml);
    // bisa diubah di dalam aplikasi: tombol MENU, atau tahan OK saat layar "server offline".
    private String defUrl() { return getString(R.string.default_url); }
    private String serverUrl() { return prefs.getString("url", defUrl()); }

    private FrameLayout root;
    private WebView web;
    private FrameLayout splash;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences prefs;
    private boolean dialogOpen = false;
    private boolean offline = false;
    private boolean splashGone = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dtv", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D1C);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D1C);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Layar pembuka: hanya logo kecil di tengah
        splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFF0B0D1C);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        int sz = (int) (72 * getResources().getDisplayMetrics().density);
        splash.addView(logo, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // halaman galat bawaan WebView diabaikan; yang ditampilkan hanya layar offline kita
                if (offline && !OFFLINE_BASE.equals(url)) return;
                hideSplash();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }
        });

        // Pemutar web memanggil ini: "Ya, Keluar" dan pengaturan nama
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void exit() { runOnUiThread(() -> finish()); }
            @android.webkit.JavascriptInterface
            public void setName(final String n) {
                runOnUiThread(() -> {
                    try { setTaskDescription(new android.app.ActivityManager.TaskDescription(n)); } catch (Throwable t) { }
                });
            }
        }, "DhanyTV");

        immersive();
        web.loadUrl(serverUrl());
        ui.postDelayed(this::hideSplash, 15000);       // jaring pengaman: splash tidak menggantung selamanya
    }

    /* ---------- splash ---------- */
    private void hideSplash() {
        if (splashGone) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(250).withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    private void showSplashAgain() {
        splashGone = false;
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        ui.postDelayed(this::hideSplash, 15000);
    }

    /* ---------- layar "server offline" (tanpa alamat / tautan) ---------- */
    private String logoDataUri() {
        try {
            Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
            return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    private void showOffline() {
        if (offline) return;
        offline = true;
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + "html,body{height:100%;margin:0;background:#0b0d1c;color:#9aa0c8;font-family:sans-serif}"
                + "body{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}"
                + "img{width:72px;height:72px;border-radius:16px}"
                + "h1{font-size:18px;color:#eceefb;margin:22px 0 6px;font-weight:700}"
                + "p{font-size:13px;margin:0}"
                + ".d{display:inline-block;width:6px;height:6px;border-radius:50%;background:#0a86d8;margin:14px 3px 0;animation:b 1.2s infinite ease-in-out}"
                + ".d:nth-child(2){animation-delay:.2s}.d:nth-child(3){animation-delay:.4s}"
                + "@keyframes b{0%,80%,100%{opacity:.2}40%{opacity:1}}"
                + "small{position:fixed;bottom:16px;left:0;right:0;font-size:11px;opacity:.4}"
                + "</style></head><body><img src='" + logoDataUri() + "'>"
                + "<h1>Server sedang offline</h1><p>Mencoba tersambung kembali…</p>"
                + "<div><span class='d'></span><span class='d'></span><span class='d'></span></div>"
                + "<small>Tahan tombol OK untuk mengubah server</small></body></html>";
        web.loadDataWithBaseURL(OFFLINE_BASE, html, "text/html", "utf-8", null);
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, 4000);
    }

    // Cek diam-diam di latar belakang; begitu server hidup, muat ulang otomatis
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (!offline || isFinishing()) return;
            final String u = serverUrl();
            new Thread(() -> {
                boolean ok = false;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(2500);
                    ok = c.getResponseCode() > 0;
                    c.disconnect();
                } catch (Exception e) { /* masih offline */ }
                final boolean up = ok;
                runOnUiThread(() -> {
                    if (!offline || isFinishing()) return;
                    if (up) {
                        offline = false;
                        showSplashAgain();
                        web.loadUrl(serverUrl());
                    } else {
                        ui.postDelayed(retry, 4000);
                    }
                });
            }).start();
        }
    };

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void askServer() {
        if (dialogOpen || isFinishing()) return;
        dialogOpen = true;
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(serverUrl());
        new AlertDialog.Builder(this)
                .setTitle("Alamat server")
                .setMessage("Contoh: http://192.168.0.101:3000/")
                .setView(in)
                .setPositiveButton("Simpan & muat", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) {
                        if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
                        prefs.edit().putString("url", u).apply();
                        offline = false;
                        showSplashAgain();
                        web.loadUrl(u);
                    }
                })
                .setNegativeButton("Batal", null)
                .setOnDismissListener(d -> { dialogOpen = false; immersive(); })
                .show();
    }

    private boolean isOk(int k) { return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER; }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askServer();
            return true;
        }
        if (offline && isOk(keyCode)) {
            e.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (offline && isOk(keyCode)) {
            askServer();
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    private void confirmExit() {
        if (dialogOpen || isFinishing()) return;
        dialogOpen = true;
        new AlertDialog.Builder(this)
                .setTitle("Yakin ingin keluar?")
                .setMessage("Siaran yang sedang diputar akan dihentikan. Masih banyak saluran lain yang menunggu.")
                .setPositiveButton("Ya, Keluar", (d, w) -> finish())
                .setNegativeButton("Batal", null)
                .setOnDismissListener(d -> { dialogOpen = false; immersive(); })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (offline) {
            confirmExit();
        } else if (web.canGoBack()) {
            web.goBack();            // pemutar web yang menangani Kembali & menampilkan konfirmasi keluar
        } else {
            confirmExit();
        }
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); ui.removeCallbacks(retry); }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        immersive();
        if (offline) { ui.removeCallbacks(retry); ui.postDelayed(retry, 1000); }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) { root.removeAllViews(); web.destroy(); }
        super.onDestroy();
    }
}
