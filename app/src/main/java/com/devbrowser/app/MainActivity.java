package com.devbrowser.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    WebView browserView;
    WebView devToolsView;
    LinearLayout devToolsPanel;
    EditText urlInput;
    TextView btnBack, btnFwd, btnRefresh, btnDevTools, btnHistory;
    ProgressBar progressBar;
    boolean devToolsOpen = false;
    boolean isHomePage = true;   // true while splash is shown, skip url update

    // ── Browsing history ──────────────────────────────────────────────────────
    static class HistoryEntry {
        String url;
        String title;
        String time;
        HistoryEntry(String url, String title, String time) {
            this.url = url; this.title = title; this.time = time;
        }
    }
    final List<HistoryEntry> history = new ArrayList<>();

    // ── JavaScript Bridge ──────────────────────────────────────────────────────
    public class DevBridge {

        @JavascriptInterface
        public void logConsole(String type, String message) {
            runOnUiThread(() -> {
                String safe = message.replace("\\","\\\\").replace("'","\\'")
                                     .replace("\n","\\n").replace("\r","");
                devToolsView.evaluateJavascript(
                    "window.dtConsole&&window.dtConsole('" + type + "','" + safe + "')", null);
            });
        }

        @JavascriptInterface
        public void logNetwork(String method, String url, String status, String duration, String type) {
            runOnUiThread(() -> {
                String su = url.replace("\\","\\\\").replace("'","\\'");
                devToolsView.evaluateJavascript(
                    "window.dtNetwork&&window.dtNetwork('" + method + "','" + su +
                    "','" + status + "','" + duration + "','" + type + "')", null);
            });
        }

        @JavascriptInterface
        public void evalInPage(String code) {
            runOnUiThread(() -> browserView.evaluateJavascript(code, value -> {
                if (value != null && !value.equals("null")) {
                    String safe = value.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
                    devToolsView.evaluateJavascript(
                        "window.dtConsole&&window.dtConsole('ret','" + safe + "')", null);
                }
            }));
        }

        @JavascriptInterface
        public void refreshStorage() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function(){var ls={},ss={},ck=document.cookie||'';" +
                "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}" +
                "for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}" +
                "return JSON.stringify({ls:ls,ss:ss,ck:ck});})()",
                value -> {
                    if (value != null && !value.equals("null"))
                        devToolsView.evaluateJavascript("window.dtStorage&&window.dtStorage(" + value + ")", null);
                }
            ));
        }

        @JavascriptInterface
        public void getPerf() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function(){var n=performance.getEntriesByType('navigation')[0]||{};" +
                "var r=performance.getEntriesByType('resource');" +
                "return JSON.stringify({" +
                "dns:Math.round((n.domainLookupEnd||0)-(n.domainLookupStart||0))," +
                "tcp:Math.round((n.connectEnd||0)-(n.connectStart||0))," +
                "ttfb:Math.round((n.responseStart||0)-(n.fetchStart||0))," +
                "dom:Math.round((n.domContentLoadedEventEnd||0)-(n.fetchStart||0))," +
                "load:Math.round((n.loadEventEnd||0)-(n.fetchStart||0))," +
                "res:r.length," +
                "resources:r.slice(0,12).map(function(x){return{" +
                "name:x.name.split('/').pop().split('?')[0].substring(0,24)||'?'," +
                "dur:Math.round(x.duration),size:Math.round(x.encodedBodySize/1024)||0};})});})()",
                value -> {
                    if (value != null && !value.equals("null"))
                        devToolsView.evaluateJavascript("window.dtPerf&&window.dtPerf(" + value + ")", null);
                }
            ));
        }

        @JavascriptInterface
        public void getDom() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function s(n,d){if(d>5)return null;" +
                "if(n.nodeType===3){var t=n.textContent.trim();return t?{type:'text',text:t.substring(0,60)}:null;}" +
                "if(n.nodeType!==1)return null;" +
                "var a={};for(var i=0;i<(n.attributes||[]).length;i++){a[n.attributes[i].name]=n.attributes[i].value.substring(0,50);}" +
                "var k=[];for(var i=0;i<n.childNodes.length;i++){var c=s(n.childNodes[i],d+1);if(c)k.push(c);}" +
                "return{type:'elem',tag:n.tagName.toLowerCase(),attrs:a,children:k};})(document.documentElement,0)",
                value -> {
                    if (value != null && !value.equals("null"))
                        devToolsView.evaluateJavascript("window.dtDom&&window.dtDom(" + value + ")", null);
                }
            ));
        }
    }

    // ── Script injected into every loaded page ────────────────────────────────
    private static final String INJECT_SCRIPT =
        "(function(){if(window.__devInjected)return;window.__devInjected=true;" +
        "['log','warn','error','info','debug'].forEach(function(m){" +
        "var o=console[m].bind(console);console[m]=function(){" +
        "var msg=Array.from(arguments).map(function(a){" +
        "try{return typeof a==='object'?JSON.stringify(a):String(a);}catch(e){return String(a);}}).join(' ');" +
        "try{Android.logConsole(m==='debug'?'log':m,msg);}catch(e){}" +
        "return o.apply(console,arguments);};});" +
        "window.onerror=function(msg,src,line){try{Android.logConsole('error',msg+' ('+src+':'+line+')');}catch(e){}};" +
        "window.addEventListener('unhandledrejection',function(e){try{Android.logConsole('error','Uncaught Promise: '+e.reason);}catch(ex){}});" +
        "var _f=window.fetch;window.fetch=function(input,init){" +
        "var url=typeof input==='string'?input:(input&&input.url)||'?';" +
        "var method=(init&&init.method)||'GET';var t0=Date.now();" +
        "return _f.apply(this,arguments).then(function(r){" +
        "try{Android.logNetwork(method,url,String(r.status),String(Date.now()-t0)+'ms','fetch');}catch(e){}return r;" +
        "}).catch(function(e){try{Android.logNetwork(method,url,'ERR',String(Date.now()-t0)+'ms','fetch');}catch(ex){}throw e;});};" +
        "var _X=window.XMLHttpRequest;window.XMLHttpRequest=function(){" +
        "var x=new _X(),method='GET',url='',t0;" +
        "var _o=x.open.bind(x);x.open=function(m,u){method=m;url=u;t0=Date.now();return _o.apply(x,arguments);};" +
        "x.addEventListener('loadend',function(){try{Android.logNetwork(method,url,String(x.status),String(Date.now()-t0)+'ms','xhr');}catch(e){}});" +
        "return x;};})();";

    // ── Splash HTML (shown before any navigation) ─────────────────────────────
    private static final String SPLASH_HTML =
        "<!DOCTYPE html><html><body style='" +
        "background:#0f1117;color:#6b7494;font-family:sans-serif;" +
        "display:flex;flex-direction:column;align-items:center;justify-content:center;" +
        "height:100vh;margin:0;text-align:center;padding:20px;box-sizing:border-box'>" +
        "<div style='font-size:52px;margin-bottom:16px'>⚡</div>" +
        "<div style='font-size:20px;color:#d4d8f0;font-weight:600;margin-bottom:8px'>DevBrowser</div>" +
        "<div style='font-size:13px;line-height:1.8;max-width:280px'>" +
        "Masukkan port atau URL di address bar<br>" +
        "<span style='color:#4f9eff;font-family:monospace'>3000</span> &nbsp;·&nbsp; " +
        "<span style='color:#4f9eff;font-family:monospace'>localhost:8080</span> &nbsp;·&nbsp; " +
        "<span style='color:#4f9eff;font-family:monospace'>http://192.168.x.x:5173</span>" +
        "</div></body></html>";

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f1117"));

        // ── Toolbar ──────────────────────────────────────────────────────────
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#1a1d27"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        btnBack = makeIconBtn("←");
        btnBack.setOnClickListener(v -> { if (browserView.canGoBack()) browserView.goBack(); });
        toolbar.addView(btnBack);

        btnFwd = makeIconBtn("→");
        btnFwd.setAlpha(0.3f);
        btnFwd.setOnClickListener(v -> { if (browserView.canGoForward()) browserView.goForward(); });
        toolbar.addView(btnFwd);

        // URL input
        urlInput = new EditText(this);
        urlInput.setHint("Port atau URL  (mis. 3000)");
        urlInput.setHintTextColor(Color.parseColor("#6b7494"));
        urlInput.setTextColor(Color.parseColor("#d4d8f0"));
        urlInput.setBackground(null);
        urlInput.setPadding(dp(10), dp(4), dp(10), dp(4));
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextSize(13f);
        urlInput.setTypeface(Typeface.MONOSPACE);
        android.graphics.drawable.GradientDrawable urlBg = new android.graphics.drawable.GradientDrawable();
        urlBg.setColor(Color.parseColor("#0f1117"));
        urlBg.setCornerRadius(dp(16));
        urlBg.setStroke(dp(1), Color.parseColor("#2e3347"));
        urlInput.setBackground(urlBg);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        urlLp.setMargins(dp(4), 0, dp(4), 0);
        urlInput.setLayoutParams(urlLp);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(urlInput.getText().toString());
                urlInput.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
        // Tap on URL bar = select all for easy replace
        urlInput.setOnFocusChangeListener((v, focused) -> {
            if (focused) urlInput.selectAll();
        });
        toolbar.addView(urlInput);

        btnRefresh = makeIconBtn("⟳");
        btnRefresh.setOnClickListener(v -> {
            if (isHomePage) return;
            if (browserView.getProgress() < 100) browserView.stopLoading();
            else browserView.reload();
        });
        toolbar.addView(btnRefresh);

        // History button
        btnHistory = makeIconBtn("☰");
        btnHistory.setOnClickListener(v -> showHistory());
        toolbar.addView(btnHistory);

        // DevTools button
        btnDevTools = new TextView(this);
        btnDevTools.setText("DEV");
        btnDevTools.setTextColor(Color.parseColor("#4f9eff"));
        btnDevTools.setTextSize(11f);
        btnDevTools.setTypeface(null, Typeface.BOLD);
        btnDevTools.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable devBg = new android.graphics.drawable.GradientDrawable();
        devBg.setColor(Color.parseColor("#222639"));
        devBg.setCornerRadius(dp(7));
        devBg.setStroke(dp(1), Color.parseColor("#2e3347"));
        btnDevTools.setBackground(devBg);
        btnDevTools.setOnClickListener(v -> toggleDevTools());
        toolbar.addView(btnDevTools);

        root.addView(toolbar);

        // ── Progress bar ─────────────────────────────────────────────────────
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progressBar.setMax(100);
        progressBar.setVisibility(View.INVISIBLE);
        try {
            progressBar.getProgressDrawable().setColorFilter(
                Color.parseColor("#4f9eff"), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {}
        root.addView(progressBar);

        // ── Browser WebView ───────────────────────────────────────────────────
        browserView = new WebView(this);
        browserView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupBrowserView();
        root.addView(browserView);

        // ── DevTools panel ────────────────────────────────────────────────────
        devToolsPanel = new LinearLayout(this);
        devToolsPanel.setOrientation(LinearLayout.VERTICAL);
        devToolsPanel.setBackgroundColor(Color.parseColor("#1a1d27"));
        devToolsPanel.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        devToolsPanel.setVisibility(View.GONE);

        // Drag handle
        View handle = new View(this);
        handle.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        handle.setBackgroundColor(Color.TRANSPARENT);
        final int[] dragState = {0, dp(320)};
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragState[0] = (int) ev.getRawY();
                    dragState[1] = devToolsPanel.getLayoutParams().height;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    int dy = dragState[0] - (int) ev.getRawY();
                    int nh = Math.max(dp(120), Math.min(
                        (int)(getResources().getDisplayMetrics().heightPixels * 0.7f),
                        dragState[1] + dy));
                    ViewGroup.LayoutParams lp = devToolsPanel.getLayoutParams();
                    lp.height = nh;
                    devToolsPanel.setLayoutParams(lp);
                    break;
            }
            return true;
        });
        devToolsPanel.addView(handle);

        devToolsView = new WebView(this);
        devToolsView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupDevToolsView();
        devToolsPanel.addView(devToolsView);
        root.addView(devToolsPanel);

        setContentView(root);

        devToolsView.loadUrl("file:///android_asset/devtools.html");

        // Load splash WITHOUT touching urlInput
        isHomePage = true;
        browserView.loadDataWithBaseURL(null, SPLASH_HTML, "text/html", "utf-8", null);
    }

    // ── Browser WebView setup ──────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupBrowserView() {
        WebSettings s = browserView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        browserView.addJavascriptInterface(new DevBridge(), "Android");

        browserView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // Let WebView handle it; onPageStarted will update URL bar
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (isHomePage) return;  // Don't update bar for splash
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(10);
                // Only update bar if user isn't typing in it
                if (!urlInput.hasFocus()) urlInput.setText(url);
                updateNavButtons();
                devToolsView.evaluateJavascript(
                    "window.dtLog&&window.dtLog('→ " + escJs(url) + "')", null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isHomePage) return;
                progressBar.setVisibility(View.INVISIBLE);
                progressBar.setProgress(100);
                // Always update bar on finish (final URL after redirects)
                urlInput.setText(url);
                updateNavButtons();
                view.evaluateJavascript(INJECT_SCRIPT, null);
                // Save to history
                view.evaluateJavascript("document.title", title -> {
                    String t = (title != null ? title : "").replaceAll("^\"|\"$", "");
                    if (t.isEmpty()) t = url;
                    String finalTitle = t;
                    String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                    runOnUiThread(() -> {
                        // Remove duplicate if same URL already at top
                        if (!history.isEmpty() && history.get(0).url.equals(url))
                            history.remove(0);
                        history.add(0, new HistoryEntry(url, finalTitle, time));
                        if (history.size() > 100) history.remove(history.size() - 1);
                    });
                });
                devToolsView.evaluateJavascript(
                    "window.dtLog&&window.dtLog('✓ " + escJs(url) + "')", null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                if (isHomePage) return;
                devToolsView.evaluateJavascript(
                    "window.dtConsole&&window.dtConsole('error','Load error " +
                    errorCode + ": " + escJs(description) + "')", null);
            }
        });

        browserView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (isHomePage) return;
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress == 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) { return false; }
            @Override
            public void onReceivedTitle(WebView view, String title) {
                // Title update from JS navigation (SPA)
                if (!isHomePage && !urlInput.hasFocus()) {
                    String url = view.getUrl();
                    if (url != null) urlInput.setText(url);
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupDevToolsView() {
        WebSettings s = devToolsView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        devToolsView.setBackgroundColor(Color.parseColor("#0f1117"));
        devToolsView.addJavascriptInterface(new DevBridge(), "Android");
    }

    // ── Navigate ───────────────────────────────────────────────────────────────
    private void navigateTo(String input) {
        String url = input.trim();
        if (url.isEmpty()) return;
        if (url.matches("\\d+"))                      url = "http://localhost:" + url;
        else if (url.matches("localhost(:\\d+)?.*"))   url = "http://" + url;
        else if (url.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) url = "http://" + url;
        else if (!url.startsWith("http://") && !url.startsWith("https://")
              && !url.startsWith("file://") && !url.startsWith("data:"))
            url = "http://" + url;

        isHomePage = false;
        urlInput.setText(url);
        browserView.loadUrl(url);
    }

    // ── History dialog ─────────────────────────────────────────────────────────
    private void showHistory() {
        if (history.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Riwayat")
                .setMessage("Belum ada riwayat penjelajahan.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        String[] items = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry e = history.get(i);
            items[i] = e.time + "  " + e.title + "\n" + e.url;
        }
        new AlertDialog.Builder(this)
            .setTitle("Riwayat (" + history.size() + ")")
            .setItems(items, (dialog, which) -> {
                navigateTo(history.get(which).url);
                hideKeyboard();
            })
            .setNeutralButton("Hapus Semua", (d, w) -> history.clear())
            .setNegativeButton("Tutup", null)
            .show();
    }

    // ── DevTools toggle ────────────────────────────────────────────────────────
    private void toggleDevTools() {
        devToolsOpen = !devToolsOpen;
        devToolsPanel.setVisibility(devToolsOpen ? View.VISIBLE : View.GONE);
        if (devToolsOpen)
            devToolsView.evaluateJavascript("window.dtRefresh&&window.dtRefresh()", null);
    }

    private void updateNavButtons() {
        btnBack.setAlpha(browserView.canGoBack() ? 1f : 0.3f);
        btnFwd.setAlpha(browserView.canGoForward() ? 1f : 0.3f);
    }

    private String escJs(String s) {
        return s.replace("\\","\\\\").replace("'","\\'").replace("\n","").replace("\r","");
    }

    private TextView makeIconBtn(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.parseColor("#6b7494"));
        btn.setTextSize(18f);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(36));
        btn.setMinHeight(dp(36));
        return btn;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
    }

    @Override
    public void onBackPressed() {
        if (devToolsOpen) { toggleDevTools(); return; }
        if (browserView.canGoBack()) browserView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onResume()  { super.onResume();  browserView.onResume(); }
    @Override protected void onPause()   { super.onPause();   browserView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); browserView.destroy(); devToolsView.destroy(); }
}
