package com.dhanytv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    // Alamat server DhanyTV (Termux di HP). Bisa diganti dari dalam aplikasi: tekan tombol MENU pada remote
    // atau akan muncul otomatis kalau server tidak bisa dihubungi.
    private static final String DEFAULT_URL = "http://192.168.0.101:3000/";

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences prefs;
    private boolean dialogOpen = false;

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
                if (r.isForMainFrame()) {
                    askServer("Tidak bisa terhubung ke server:\n" + r.getUrl() + "\n(" + e.getDescription() + ")");
                }
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

        // Pemutar web memanggil ini saat pengguna menekan "Ya, Keluar"
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void exit() { runOnUiThread(() -> finish()); }
        }, "DhanyTV");

        immersive();
        web.loadUrl(prefs.getString("url", DEFAULT_URL));
    }

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

    private void askServer(String message) {
        if (dialogOpen || isFinishing()) return;
        dialogOpen = true;
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(prefs.getString("url", DEFAULT_URL));
        new AlertDialog.Builder(this)
                .setTitle("Alamat server DhanyTV")
                .setMessage(message == null ? "Contoh: http://192.168.0.101:3000/" : message)
                .setView(in)
                .setPositiveButton("Simpan & muat", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) {
                        if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
                        prefs.edit().putString("url", u).apply();
                        web.loadUrl(u);
                    }
                })
                .setNegativeButton("Coba lagi", (d, w) -> web.loadUrl(prefs.getString("url", DEFAULT_URL)))
                .setOnDismissListener(d -> { dialogOpen = false; immersive(); })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askServer(null);
            return true;
        }
        return super.onKeyDown(keyCode, e);
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
        } else if (web.canGoBack()) {
            web.goBack();            // pemutar web yang menangani Kembali & menampilkan konfirmasi keluar
        } else {
            confirmExit();           // cadangan: halaman gagal dimuat
        }
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); immersive(); }

    @Override
    protected void onDestroy() {
        if (web != null) { root.removeAllViews(); web.destroy(); }
        super.onDestroy();
    }
}
