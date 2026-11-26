package com.dhanytv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String OFFLINE_BASE = "http://offline.local/";

    // Halaman dimuat dari alamat https "virtual" supaya WebView menganggapnya konteks aman. DRM (EME: ClearKey/Widevine)
    // hanya aktif di https/localhost, sedangkan alamat server di LAN (http://192.168.x.x:3000) tidak aman.
    // Hanya halaman utamanya yang diambil lewat sini; API dan video tetap langsung ke server (?server=...).
    // ".invalid" tidak pernah punya DNS, jadi tidak ada permintaan yang bocor ke internet.
    private static final String SECURE_HOST = "app.dhanytv.invalid";
    private static final String SECURE_BASE = "https://" + SECURE_HOST + "/";

    // Repositori GitHub tempat start_tunnel.py menulis server.json (alamat Cloudflare Quick Tunnel terbaru)
    private static final String GH_REPO = "Tesjiwa/A-Stream";

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
    // Layar pembuka minimal tampil sekian milidetik supaya logo sempat terlihat jelas
    private static final long MIN_SPLASH_MS = 3000;
    private long splashAt = 0;
    private final Runnable hideSplashRun = new Runnable() { @Override public void run() { hideSplash(); } };
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
        splashAt = SystemClock.uptimeMillis();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        float dn = getResources().getDisplayMetrics().density;
        int sz = (int) (170 * dn);
        splash.addView(logo, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        logo.setAlpha(0f);
        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800).start();
        TextView ver = new TextView(this);
        ver.setTextColor(0x66FFFFFF);
        ver.setTextSize(11);
        try { ver.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); } catch (Throwable t) { }
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        vp.bottomMargin = (int) (24 * dn);
        splash.addView(ver, vp);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.clearCache(true);      // buang halaman versi lama (localStorage/token tidak ikut terhapus)

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                if (uri != null && SECURE_HOST.equals(uri.getHost())) return fromServer(r);
                return super.shouldInterceptRequest(v, r);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // halaman galat bawaan WebView diabaikan; yang ditampilkan hanya layar offline kita
                if (offline && !OFFLINE_BASE.equals(url)) return;
                focusWeb();
                hideSplash();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Widevine di WebView butuh izin "ID media terlindungi". Hanya itu yang diizinkan; kamera/mikrofon ditolak.
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                java.util.ArrayList<String> ok = new java.util.ArrayList<>();
                for (String res : request.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
                }
                if (ok.isEmpty()) request.deny();
                else request.grant(ok.toArray(new String[0]));
            }

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
            public void rotate(final boolean land) {
                runOnUiThread(() -> setRequestedOrientation(land
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            }
            @android.webkit.JavascriptInterface
            public void setName(final String n) {
                runOnUiThread(() -> {
                    try { setTaskDescription(new android.app.ActivityManager.TaskDescription(n)); } catch (Throwable t) { }
                });
            }
        }, "DhanyTV");

        immersive();
        loadApp(serverUrl());
        ui.postDelayed(this::hideSplash, 15000);       // jaring pengaman: splash tidak menggantung selamanya
    }

    /* ---------- muat aplikasi dari aset lokal lewat asal https virtual ---------- */
    // Halaman pemutar (dhanytv.html, tersimpan di assets/index.html) SELALU dimuat dari dalam aplikasi lewat alamat https
    // virtual (konteks aman untuk DRM). Aplikasi tidak pernah membuka alamat server / trycloudflare.com sebagai halaman
    // awal, jadi tunnel lama yang sudah mati tidak bisa memunculkan Cloudflare Error 1033. Setelah termuat, halaman
    // sendiri mengambil server_url terbaru dari server.json di GitHub (repo GH_REPO) sebelum meminta playlist/stream.
    // Alamat manual (mis. server LAN) tetap diteruskan lewat ?server=...; alamat trycloudflare.com TIDAK diteruskan
    // karena berumur pendek, halaman mencarinya sendiri di GitHub.
    private void loadApp(String u) {
        String t = u == null ? "" : u.trim();
        String q = "";
        if (!t.isEmpty() && !t.toLowerCase(java.util.Locale.ROOT).matches("^https?://[^/]*\\.trycloudflare\\.com(:\\d+)?(/.*)?$")) {
            try { q = "?server=" + URLEncoder.encode(t, "UTF-8"); } catch (Exception e) { q = ""; }
        }
        // cache-busting: alamat halaman berbeda setiap aplikasi memuat
        q += (q.isEmpty() ? "?" : "&") + "_v=" + System.currentTimeMillis();
        web.loadUrl(SECURE_BASE + q);
        focusWeb();
    }

    // Dipanggil WebView (thread latar belakang) untuk alamat https virtual: sajikan halaman utama dari aset lokal.
    private WebResourceResponse fromServer(WebResourceRequest r) {
        String p = r.getUrl().getPath();
        boolean page = p == null || p.isEmpty() || "/".equals(p) || "/index.html".equals(p) || "/dhanytv.html".equals(p);
        if (!"GET".equalsIgnoreCase(r.getMethod()) || !page) {
            return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                    new HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
        }
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, getAssets().open("index.html"));
        } catch (Exception e) {
            return null;
        }
    }

    // Alamat tunnel (Cloudflare Quick Tunnel) berganti setiap server dinyalakan: start_tunnel.py menulis alamat terbaru
    // ke server.json di repositori GitHub. Panggil HANYA dari thread latar belakang. true bila server.json terbaca.
    private boolean refreshFromGithub() {
        if (GH_REPO == null || GH_REPO.isEmpty()) return false;
        String[] urls = {
                "https://api.github.com/repos/" + GH_REPO + "/contents/server.json",
                "https://raw.githubusercontent.com/" + GH_REPO + "/HEAD/server.json?t=" + System.currentTimeMillis()
        };
        for (int i = 0; i < urls.length; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(urls[i]).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (i == 0) c.setRequestProperty("Accept", "application/vnd.github.raw");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                java.io.InputStream in = c.getInputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
                c.disconnect();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"server_url\"\\s*:\\s*\"(https?://[A-Za-z0-9.:-]+)\"")
                        .matcher(bo.toString("UTF-8"));
                if (m.find()) {
                    String u = m.group(1);
                    if (!u.equals(serverUrl())) prefs.edit().putString("url", u).apply();
                    return true;
                }
            } catch (Exception e) { /* coba sumber berikutnya */ }
        }
        return false;
    }

    // Server DhanyTV benar-benar menjawab? (/api/ping; tunnel yang mati menjawab 530, bukan 200)
    private boolean reachable(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u.trim().replaceAll("/+$", "") + "/api/ping").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            boolean ok = c.getResponseCode() == 200;
            c.disconnect();
            return ok;
        } catch (Exception e) { return false; }
    }

    /* ---------- splash ---------- */
    private void hideSplash() {
        if (splashGone) return;
        long left = MIN_SPLASH_MS - (SystemClock.uptimeMillis() - splashAt);
        if (left > 0) {                      // belum cukup lama: tunda
            ui.removeCallbacks(hideSplashRun);
            ui.postDelayed(hideSplashRun, left);
            return;
        }
        splashGone = true;
        splash.animate().alpha(0f).setDuration(250).withEndAction(() -> { splash.setVisibility(View.GONE); focusWeb(); }).start();
    }

    private void showSplashAgain() {
        splashGone = false;
        splashAt = SystemClock.uptimeMillis() - (MIN_SPLASH_MS - 1200);     // muncul lagi: cukup ~1,2 detik
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        ui.postDelayed(this::hideSplash, 15000);
    }

    /* ---------- layar "server offline" (tanpa alamat / tautan) ---------- */
    private String logoDataUri() {
        try {
            Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.splash_logo);
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
                + "img{width:110px;height:110px;object-fit:contain}"
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
                boolean ok = reachable(u);
                if (!ok && refreshFromGithub()) ok = reachable(serverUrl());     // tunnel berganti alamat?
                final boolean up = ok;
                runOnUiThread(() -> {
                    if (!offline || isFinishing()) return;
                    if (up) {
                        offline = false;
                        showSplashAgain();
                        loadApp(serverUrl());
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
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        immersive();
        // setelah memutar layar: paksa WebView menghitung ulang ukuran supaya video tidak bergeser / membesar
        ui.postDelayed(() -> {
            if (web == null) return;
            web.requestLayout();
            web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null);
        }, 350);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { immersive(); focusWeb(); }
    }

    // Tombol remote hanya sampai ke halaman bila WebView memegang fokus
    private void focusWeb() {
        if (web != null && customView == null && !web.hasFocus()) web.requestFocus();
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
                        loadApp(u);
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
        focusWeb();
        if (offline) { ui.removeCallbacks(retry); ui.postDelayed(retry, 1000); }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) { root.removeAllViews(); web.destroy(); }
        super.onDestroy();
    }
}
