package com.devbrowser.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // ── Views ──────────────────────────────────────────────────────────────────
    WebView     browserView;
    WebView     devToolsView;
    WebView     desktopWebView;         // off-screen, for desktop screenshot
    FrameLayout captureContainer;       // hosts desktopWebView off-screen
    LinearLayout devToolsPanel;
    EditText    urlInput;
    TextView    btnBack, btnFwd, btnRefresh, btnDevTools, btnHistory, btnCookie, btnShot;
    ProgressBar progressBar;

    // ── State ──────────────────────────────────────────────────────────────────
    boolean devToolsOpen  = false;
    boolean isHomePage    = true;
    boolean cookiesEnabled = true;
    ProgressDialog captureDialog;

    // ── Port quick-launch ──────────────────────────────────────────────────────
    static final int[][] PORT_GROUPS = {
        {3000, 3001, 3030, 3333},
        {4000, 4200, 4321, 4173},
        {5000, 5173, 5174, 5500},
        {8000, 8080, 8081, 8888},
        {9000, 9090, 9229, 19006},
    };
    static final String[] PORT_LABELS = {
        "React / Next.js",
        "Angular / Astro / Vite preview",
        "Flask / Vue / Vite / LiveServer",
        "Django / Spring / Apache / Alt",
        "Custom / Nodemon / Node Debug / RN Metro",
    };
    static final String[][] PORT_TAGS = {
        {"React","Next.js","Gatsby","CRA"},
        {"Angular","Astro","Vite prev","Custom"},
        {"Flask","Vue CLI","Vite","LiveServer"},
        {"Django","Spring","Apache","Alt"},
        {"Custom","Nodemon","Node Dbg","RN Metro"},
    };

    // ── History ────────────────────────────────────────────────────────────────
    static class HistoryEntry {
        String url, title, time;
        HistoryEntry(String u, String t, String ts) { url=u; title=t; time=ts; }
    }
    final List<HistoryEntry> history = new ArrayList<>();

    // ── UA strings ────────────────────────────────────────────────────────────
    static final String DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36";

    // ── JS Bridge ─────────────────────────────────────────────────────────────
    public class DevBridge {
        @JavascriptInterface
        public void logConsole(String type, String msg) {
            runOnUiThread(() -> {
                String s = msg.replace("\\","\\\\").replace("'","\\'")
                              .replace("\n","\\n").replace("\r","");
                devToolsView.evaluateJavascript(
                    "window.dtConsole&&window.dtConsole('"+type+"','"+s+"')", null);
            });
        }
        @JavascriptInterface
        public void logNetwork(String method, String url, String status, String dur, String type) {
            runOnUiThread(() -> {
                String u = url.replace("\\","\\\\").replace("'","\\'");
                devToolsView.evaluateJavascript(
                    "window.dtNetwork&&window.dtNetwork('"+method+"','"+u+
                    "','"+status+"','"+dur+"','"+type+"')", null);
            });
        }
        @JavascriptInterface
        public void evalInPage(String code) {
            runOnUiThread(() -> browserView.evaluateJavascript(code, val -> {
                if (val != null && !val.equals("null")) {
                    String s = val.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
                    devToolsView.evaluateJavascript(
                        "window.dtConsole&&window.dtConsole('ret','"+s+"')", null);
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
                val -> { if (val != null && !val.equals("null"))
                    devToolsView.evaluateJavascript("window.dtStorage&&window.dtStorage("+val+")", null); }
            ));
        }
        @JavascriptInterface
        public void getPerf() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function(){var n=performance.getEntriesByType('navigation')[0]||{};" +
                "var r=performance.getEntriesByType('resource');" +
                "return JSON.stringify({dns:Math.round((n.domainLookupEnd||0)-(n.domainLookupStart||0))," +
                "tcp:Math.round((n.connectEnd||0)-(n.connectStart||0))," +
                "ttfb:Math.round((n.responseStart||0)-(n.fetchStart||0))," +
                "dom:Math.round((n.domContentLoadedEventEnd||0)-(n.fetchStart||0))," +
                "load:Math.round((n.loadEventEnd||0)-(n.fetchStart||0)),res:r.length," +
                "resources:r.slice(0,12).map(function(x){return{" +
                "name:x.name.split('/').pop().split('?')[0].substring(0,24)||'?'," +
                "dur:Math.round(x.duration),size:Math.round(x.encodedBodySize/1024)||0};})});})()",
                val -> { if (val != null && !val.equals("null"))
                    devToolsView.evaluateJavascript("window.dtPerf&&window.dtPerf("+val+")", null); }
            ));
        }
        @JavascriptInterface
        public void getDom() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function s(n,d){if(d>5)return null;" +
                "if(n.nodeType===3){var t=n.textContent.trim();return t?{type:'text',text:t.substring(0,60)}:null;}" +
                "if(n.nodeType!==1)return null;" +
                "var a={};for(var i=0;i<(n.attributes||[]).length;i++)a[n.attributes[i].name]=n.attributes[i].value.substring(0,50);" +
                "var k=[];for(var i=0;i<n.childNodes.length;i++){var c=s(n.childNodes[i],d+1);if(c)k.push(c);}" +
                "return{type:'elem',tag:n.tagName.toLowerCase(),attrs:a,children:k};})(document.documentElement,0)",
                val -> { if (val != null && !val.equals("null"))
                    devToolsView.evaluateJavascript("window.dtDom&&window.dtDom("+val+")", null); }
            ));
        }
        @JavascriptInterface
        public void openPort(int port) {
            runOnUiThread(() -> navigateTo("localhost:" + port));
        }
    }

    // ── Inject script ──────────────────────────────────────────────────────────
    static final String INJECT =
        "(function(){if(window.__devInjected)return;window.__devInjected=true;" +
        "['log','warn','error','info','debug'].forEach(function(m){var o=console[m].bind(console);" +
        "console[m]=function(){var msg=Array.from(arguments).map(function(a){" +
        "try{return typeof a==='object'?JSON.stringify(a):String(a);}catch(e){return String(a);}}).join(' ');" +
        "try{Android.logConsole(m==='debug'?'log':m,msg);}catch(e){}return o.apply(console,arguments);};});" +
        "window.onerror=function(msg,src,line){try{Android.logConsole('error',msg+' ('+src+':'+line+')');}catch(e){}};" +
        "window.addEventListener('unhandledrejection',function(e){try{Android.logConsole('error','Promise: '+e.reason);}catch(ex){}});" +
        "var _f=window.fetch;window.fetch=function(inp,ini){" +
        "var url=typeof inp==='string'?inp:(inp&&inp.url)||'?';var m=(ini&&ini.method)||'GET';var t=Date.now();" +
        "return _f.apply(this,arguments)" +
        ".then(function(r){try{Android.logNetwork(m,url,String(r.status),String(Date.now()-t)+'ms','fetch');}catch(e){}return r;})" +
        ".catch(function(e){try{Android.logNetwork(m,url,'ERR',String(Date.now()-t)+'ms','fetch');}catch(x){}throw e;});};" +
        "var _X=window.XMLHttpRequest;window.XMLHttpRequest=function(){var x=new _X(),m='GET',u='',t;" +
        "var _o=x.open.bind(x);x.open=function(a,b){m=a;u=b;t=Date.now();return _o.apply(x,arguments);};" +
        "x.addEventListener('loadend',function(){try{Android.logNetwork(m,u,String(x.status),String(Date.now()-t)+'ms','xhr');}catch(e){}});" +
        "return x;};})();";

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // MUST be called before any WebView is instantiated
        WebView.enableSlowWholeDocumentDraw();

        // ── Root: FrameLayout wrapping everything (allows off-screen overlay) ─
        FrameLayout rootFrame = new FrameLayout(this);

        // ── Main vertical layout ──────────────────────────────────────────────
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(Color.parseColor("#0f1117"));
        main.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Toolbar ───────────────────────────────────────────────────────────
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

        urlInput = new EditText(this);
        urlInput.setHint("Port atau URL  (mis. 3000)");
        urlInput.setHintTextColor(Color.parseColor("#6b7494"));
        urlInput.setTextColor(Color.parseColor("#d4d8f0"));
        urlInput.setBackground(null);
        urlInput.setPadding(dp(10), 0, dp(10), 0);
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
        urlInput.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(urlInput.getText().toString());
                urlInput.clearFocus(); hideKeyboard(); return true;
            }
            return false;
        });
        urlInput.setOnFocusChangeListener((v, focused) -> { if (focused) urlInput.selectAll(); });
        toolbar.addView(urlInput);

        btnRefresh = makeIconBtn("⟳");
        btnRefresh.setOnClickListener(v -> {
            if (isHomePage) return;
            if (browserView.getProgress() < 100) browserView.stopLoading();
            else browserView.reload();
        });
        toolbar.addView(btnRefresh);

        btnShot = makeTagBtn("📸", "#e8a838");
        btnShot.setOnClickListener(v -> showScreenshotMenu());
        toolbar.addView(btnShot);

        btnCookie = makeTagBtn("🍪", "#4ec9b0");
        btnCookie.setOnClickListener(v -> showCookieMenu());
        toolbar.addView(btnCookie);

        btnHistory = makeIconBtn("☰");
        btnHistory.setOnClickListener(v -> showHistory());
        toolbar.addView(btnHistory);

        btnDevTools = makeTagBtn("DEV", "#4f9eff");
        btnDevTools.setOnClickListener(v -> toggleDevTools());
        toolbar.addView(btnDevTools);

        main.addView(toolbar);

        // ── Progress bar ──────────────────────────────────────────────────────
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progressBar.setMax(100);
        progressBar.setVisibility(View.INVISIBLE);
        try {
            progressBar.getProgressDrawable().setColorFilter(
                Color.parseColor("#4f9eff"), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {}
        main.addView(progressBar);

        // ── Browser WebView ───────────────────────────────────────────────────
        browserView = new WebView(this);
        browserView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupBrowserView();
        main.addView(browserView);

        // ── DevTools panel ────────────────────────────────────────────────────
        devToolsPanel = new LinearLayout(this);
        devToolsPanel.setOrientation(LinearLayout.VERTICAL);
        devToolsPanel.setBackgroundColor(Color.parseColor("#1a1d27"));
        devToolsPanel.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        devToolsPanel.setVisibility(View.GONE);

        View handle = new View(this);
        handle.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        final int[] ds = {0, dp(320)};
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    ds[0] = (int) ev.getRawY(); ds[1] = devToolsPanel.getLayoutParams().height; break;
                case android.view.MotionEvent.ACTION_MOVE:
                    int dy = ds[0] - (int) ev.getRawY();
                    int nh = Math.max(dp(120), Math.min(
                        (int)(getResources().getDisplayMetrics().heightPixels * 0.7f), ds[1] + dy));
                    devToolsPanel.getLayoutParams().height = nh;
                    devToolsPanel.requestLayout(); break;
            }
            return true;
        });
        devToolsPanel.addView(handle);

        devToolsView = new WebView(this);
        devToolsView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupDevToolsView();
        devToolsPanel.addView(devToolsView);
        main.addView(devToolsPanel);

        // ── Off-screen WebView for desktop screenshot ─────────────────────────
        captureContainer = new FrameLayout(this);
        captureContainer.setLayoutParams(new FrameLayout.LayoutParams(dp(1280), dp(900)));
        // Push below visible area
        captureContainer.setTranslationY(getResources().getDisplayMetrics().heightPixels + dp(100));

        desktopWebView = new WebView(this);
        desktopWebView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupDesktopWebView();
        captureContainer.addView(desktopWebView);

        rootFrame.addView(main);
        rootFrame.addView(captureContainer);
        setContentView(rootFrame);

        devToolsView.loadUrl("file:///android_asset/devtools.html");
        isHomePage = true;
        showSplash();
    }

    // ── Splash with port grid ──────────────────────────────────────────────────
    private void showSplash() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
          .append("<meta charset='UTF-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<style>")
          .append("*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}")
          .append("body{background:#0f1117;color:#d4d8f0;font-family:-apple-system,sans-serif;")
          .append("padding:20px 14px;overflow-y:auto;}")
          .append(".hero{text-align:center;padding:24px 0 20px}")
          .append(".logo{font-size:48px;margin-bottom:8px}")
          .append(".title{font-size:20px;font-weight:700;margin-bottom:4px}")
          .append(".sub{font-size:12px;color:#6b7494;line-height:1.6}")
          .append(".sec-label{font-size:10px;color:#6b7494;text-transform:uppercase;")
          .append("letter-spacing:.8px;margin:14px 0 7px}")
          .append(".row{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-bottom:4px}")
          .append(".btn{background:#1a1d27;border:1.5px solid #2e3347;border-radius:10px;")
          .append("padding:10px 4px;text-align:center;cursor:pointer}")
          .append(".btn:active{background:#222639;border-color:#4f9eff}")
          .append(".num{font-size:15px;font-weight:700;font-family:monospace;color:#4f9eff;margin-bottom:2px}")
          .append(".tag{font-size:10px;color:#6b7494;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
          .append(".tip{background:#1a1d27;border:1px solid #2e3347;border-radius:10px;")
          .append("padding:12px;font-size:12px;color:#6b7494;line-height:1.7;margin-top:16px}")
          .append(".tip code{color:#4fcfff;font-family:monospace}")
          .append("</style></head><body>")
          .append("<div class='hero'><div class='logo'>⚡</div>")
          .append("<div class='title'>DevBrowser</div>")
          .append("<div class='sub'>Debug &amp; preview proyek lokal</div></div>");

        for (int g = 0; g < PORT_GROUPS.length; g++) {
            sb.append("<div class='sec-label'>").append(PORT_LABELS[g]).append("</div>")
              .append("<div class='row'>");
            for (int p = 0; p < PORT_GROUPS[g].length; p++) {
                int port = PORT_GROUPS[g][p];
                sb.append("<div class='btn' onclick='Android.openPort(").append(port).append(")'> ")
                  .append("<div class='num'>").append(port).append("</div>")
                  .append("<div class='tag'>").append(PORT_TAGS[g][p]).append("</div></div>");
            }
            sb.append("</div>");
        }
        sb.append("<div class='tip'>")
          .append("Ketik di address bar: <code>3000</code> · <code>localhost:8080</code> · <code>192.168.x.x:5173</code>")
          .append("</div><div style='height:12px'></div></body></html>");

        browserView.addJavascriptInterface(new DevBridge(), "Android");
        browserView.loadDataWithBaseURL(null, sb.toString(), "text/html", "utf-8", null);
    }

    // ═══════════════════════════════════════════════════════
    // SCREENSHOT
    // ═══════════════════════════════════════════════════════

    private void showScreenshotMenu() {
        if (isHomePage) {
            Toast.makeText(this, "Buka halaman terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = browserView.getUrl();
        if (url == null || url.isEmpty()) return;

        String[] opts = {
            "📱  Mobile — lebar saat ini, full page",
            "🖥️  Desktop — 1280px, full page",
            "📸  Keduanya sekaligus"
        };
        new AlertDialog.Builder(this)
            .setTitle("Ambil Screenshot")
            .setItems(opts, (d, w) -> {
                switch (w) {
                    case 0: takeMobileShot(() -> {}); break;
                    case 1: takeDesktopShot(url, () -> {}); break;
                    case 2: takeMobileShot(() -> takeDesktopShot(url, () -> {})); break;
                }
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    // ── Mobile: scroll-and-stitch on browserView ──────────────────────────────
    private void takeMobileShot(Runnable callback) {
        showProgress("Mengambil screenshot mobile...");
        browserView.evaluateJavascript(
            "(function(){return JSON.stringify({" +
            "w:Math.max(document.documentElement.scrollWidth,window.innerWidth||0)," +
            "h:Math.max(document.documentElement.scrollHeight,window.innerHeight||0)" +
            "});})()",
            raw -> runOnUiThread(() -> {
                int w = Math.max(parseInt(raw, "w"), browserView.getWidth());
                int h = Math.min(Math.max(parseInt(raw, "h"), browserView.getHeight()), 20000);
                scrollAndStitch(browserView, w, h, "mobile", callback);
            })
        );
    }

    // ── Desktop: load in hidden 1280px WebView then scroll-and-stitch ─────────
    private void takeDesktopShot(String url, Runnable callback) {
        showProgress("Memuat versi desktop...");
        desktopWebView.getSettings().setUserAgentString(DESKTOP_UA);
        desktopWebView.setWebViewClient(new WebViewClient() {
            boolean fired = false;
            @Override
            public void onPageFinished(WebView view, String u) {
                if (fired) return;
                fired = true;
                // Inject wide viewport
                view.evaluateJavascript(
                    "(function(){var m=document.querySelector('meta[name=viewport]');" +
                    "if(m)m.content='width=1280';else{m=document.createElement('meta');" +
                    "m.name='viewport';m.content='width=1280';document.head.appendChild(m);" +
                    "}})()", null);
                view.postDelayed(() -> {
                    view.evaluateJavascript(
                        "(function(){return JSON.stringify({" +
                        "w:Math.max(document.documentElement.scrollWidth,1280)," +
                        "h:Math.max(document.documentElement.scrollHeight,900)" +
                        "});})()",
                        raw -> runOnUiThread(() -> {
                            int pw = Math.max(parseInt(raw, "w"), dp(1280));
                            int ph = Math.min(Math.max(parseInt(raw, "h"), dp(900)), 20000);
                            // Resize captureContainer to actual page size
                            captureContainer.getLayoutParams().width  = pw;
                            captureContainer.getLayoutParams().height = ph;
                            captureContainer.requestLayout();
                            view.postDelayed(() ->
                                scrollAndStitch(desktopWebView, pw, ph, "desktop", callback), 300);
                        })
                    );
                }, 800); // wait for layout
            }
        });
        desktopWebView.loadUrl(url);
    }

    // ── Core scroll-and-stitch ────────────────────────────────────────────────
    private void scrollAndStitch(WebView wv, int pageW, int pageH,
                                 String label, Runnable callback) {
        updateProgress("Merender " + label + "… 0%");
        Bitmap full = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888);
        full.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(full);
        int viewH = wv.getHeight();
        if (viewH <= 0) viewH = dp(800);
        stitchSection(wv, canvas, 0, pageH, viewH, pageW, label, full, callback);
    }

    private void stitchSection(WebView wv, Canvas canvas, int scrollY, int totalH,
                               int viewH, int pageW, String label, Bitmap full, Runnable callback) {
        if (scrollY >= totalH) {
            wv.scrollTo(0, 0);
            hideProgress();
            saveAndShow(full, label, callback);
            return;
        }
        int pct = (int)((float) scrollY / totalH * 100);
        updateProgress("Merender " + label + "… " + pct + "%");
        wv.scrollTo(0, scrollY);
        final int sy = scrollY;
        wv.postDelayed(() -> {
            try {
                int secH = Math.min(viewH, totalH - sy);
                Bitmap sec = Bitmap.createBitmap(pageW, secH, Bitmap.Config.ARGB_8888);
                sec.eraseColor(Color.WHITE);
                wv.draw(new Canvas(sec));
                canvas.drawBitmap(sec, 0, sy, null);
                sec.recycle();
            } catch (OutOfMemoryError e) {
                runOnUiThread(() -> Toast.makeText(this,
                    "Halaman terlalu besar, screenshot terpotong", Toast.LENGTH_SHORT).show());
                wv.scrollTo(0, 0); hideProgress();
                saveAndShow(full, label, callback);
                return;
            }
            stitchSection(wv, canvas, scrollY + viewH, totalH, viewH, pageW, label, full, callback);
        }, 100);
    }

    // ── Save and show result ──────────────────────────────────────────────────
    private void saveAndShow(Bitmap bitmap, String label, Runnable callback) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String name = "devbrowser_" + label + "_" + ts + ".png";
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "DevBrowser");
            dir.mkdirs();
            File file = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos);
            fos.flush(); fos.close();

            runOnUiThread(() -> showShotResult(bitmap, file, callback));
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Gagal simpan: " + e.getMessage(), Toast.LENGTH_LONG).show();
                bitmap.recycle();
                callback.run();
            });
        }
    }

    private void showShotResult(Bitmap bmp, File file, Runnable callback) {
        // Build dialog layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0f1117"));
        layout.setPadding(dp(14), dp(10), dp(14), dp(10));

        // Thumbnail
        ImageView thumb = new ImageView(this);
        thumb.setImageBitmap(bmp);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackgroundColor(Color.parseColor("#1a1d27"));
        int thumbH = (int)(getResources().getDisplayMetrics().widthPixels * 0.6f);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, thumbH));
        layout.addView(thumb);

        // Info text
        TextView info = new TextView(this);
        info.setText("  " + bmp.getWidth() + " × " + bmp.getHeight() + " px   |   " + file.getName());
        info.setTextColor(Color.parseColor("#6b7494"));
        info.setTextSize(11f);
        info.setPadding(0, dp(8), 0, dp(4));
        layout.addView(info);

        new AlertDialog.Builder(this)
            .setView(layout)
            .setTitle("✅ Screenshot Tersimpan")
            .setPositiveButton("📤 Bagikan", (d, w) -> { shareFile(file); bmp.recycle(); callback.run(); })
            .setNeutralButton("🖼️ Buka", (d, w) -> { openFile(file); bmp.recycle(); callback.run(); })
            .setNegativeButton("Tutup", (d, w) -> { bmp.recycle(); callback.run(); })
            .show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Bagikan Screenshot"));
        } catch (Exception e) {
            Toast.makeText(this, file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Path: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Progress dialog ───────────────────────────────────────────────────────
    @SuppressWarnings("deprecation")
    private void showProgress(String msg) {
        runOnUiThread(() -> {
            if (captureDialog != null) captureDialog.dismiss();
            captureDialog = new ProgressDialog(this);
            captureDialog.setMessage(msg);
            captureDialog.setCancelable(false);
            captureDialog.show();
        });
    }
    private void updateProgress(String msg) {
        runOnUiThread(() -> { if (captureDialog != null) captureDialog.setMessage(msg); });
    }
    private void hideProgress() {
        runOnUiThread(() -> {
            if (captureDialog != null) { captureDialog.dismiss(); captureDialog = null; }
        });
    }

    // ── JSON int parser ───────────────────────────────────────────────────────
    private int parseInt(String json, String key) {
        try {
            String pattern = "\"" + key + "\":";
            int idx = json.indexOf(pattern);
            if (idx < 0) return 0;
            idx += pattern.length();
            int end = idx;
            while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
            return Integer.parseInt(json.substring(idx, end));
        } catch (Exception e) { return 0; }
    }

    // ═══════════════════════════════════════════════════════
    // COOKIE MANAGER
    // ═══════════════════════════════════════════════════════

    private void showCookieMenu() {
        CookieManager cm = CookieManager.getInstance();
        String curUrl = isHomePage ? null : browserView.getUrl();
        String pageCookies = (curUrl != null) ? cm.getCookie(curUrl) : null;
        String status = cookiesEnabled ? "🟢 Cookie Aktif" : "🔴 Cookie Nonaktif";

        List<String> opts = new ArrayList<>();
        opts.add(cookiesEnabled ? "Nonaktifkan Cookie" : "Aktifkan Cookie");
        opts.add("Hapus Semua Cookie");
        if (pageCookies != null && !pageCookies.isEmpty()) {
            opts.add("Lihat Cookie Halaman Ini");
            opts.add("Hapus Cookie Halaman Ini");
        }
        new AlertDialog.Builder(this)
            .setTitle(status)
            .setItems(opts.toArray(new String[0]), (d, w) -> {
                String chosen = opts.get(w);
                if (chosen.contains("Aktifkan") || chosen.contains("Nonaktifkan")) {
                    cookiesEnabled = !cookiesEnabled;
                    cm.setAcceptCookie(cookiesEnabled);
                    cm.setAcceptThirdPartyCookies(browserView, cookiesEnabled);
                    btnCookie.setText(cookiesEnabled ? "🍪" : "🚫");
                    btnCookie.setAlpha(cookiesEnabled ? 1f : 0.5f);
                    Toast.makeText(this, cookiesEnabled ? "Cookie aktif ✓" : "Cookie nonaktif", Toast.LENGTH_SHORT).show();
                } else if (chosen.equals("Hapus Semua Cookie")) {
                    new AlertDialog.Builder(this)
                        .setTitle("Hapus Semua Cookie?")
                        .setPositiveButton("Hapus", (d2, w2) -> {
                            cm.removeAllCookies(v ->
                                runOnUiThread(() -> Toast.makeText(this, "Semua cookie dihapus ✓", Toast.LENGTH_SHORT).show()));
                            cm.flush();
                        })
                        .setNegativeButton("Batal", null).show();
                } else if (chosen.equals("Lihat Cookie Halaman Ini")) {
                    String cookies = cm.getCookie(curUrl);
                    if (cookies == null) cookies = "(kosong)";
                    StringBuilder sb = new StringBuilder();
                    for (String pair : cookies.split(";")) sb.append(pair.trim()).append("\n\n");
                    new AlertDialog.Builder(this)
                        .setTitle("Cookie halaman ini")
                        .setMessage(sb.toString().trim())
                        .setPositiveButton("Tutup", null).show();
                } else if (chosen.equals("Hapus Cookie Halaman Ini")) {
                    new AlertDialog.Builder(this)
                        .setTitle("Hapus cookie halaman ini?")
                        .setPositiveButton("Hapus", (d2, w2) -> {
                            cm.removeAllCookies(null); cm.flush();
                            Toast.makeText(this, "Cookie dihapus ✓", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Batal", null).show();
                }
            })
            .setNegativeButton("Tutup", null)
            .show();
    }

    // ═══════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════

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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browserView, true);
        browserView.addJavascriptInterface(new DevBridge(), "Android");

        browserView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) { return false; }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap fav) {
                if (isHomePage) return;
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(10);
                if (!urlInput.hasFocus()) urlInput.setText(url);
                updateNavBtns();
                devToolsView.evaluateJavascript("window.dtLog&&window.dtLog('→ "+escJs(url)+"')", null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isHomePage) return;
                progressBar.setVisibility(View.INVISIBLE);
                urlInput.setText(url);
                updateNavBtns();
                view.evaluateJavascript(INJECT, null);
                view.evaluateJavascript("document.title", title -> {
                    String t = (title != null ? title : "").replaceAll("^\"|\"$","");
                    if (t.isEmpty()) t = url;
                    String ft = t;
                    String ts = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                    runOnUiThread(() -> {
                        if (!history.isEmpty() && history.get(0).url.equals(url)) history.remove(0);
                        history.add(0, new HistoryEntry(url, ft, ts));
                        if (history.size() > 100) history.remove(history.size()-1);
                    });
                });
                devToolsView.evaluateJavascript("window.dtLog&&window.dtLog('✓ "+escJs(url)+"')", null);
            }
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                if (isHomePage) return;
                devToolsView.evaluateJavascript(
                    "window.dtConsole&&window.dtConsole('error','Error "+code+": "+escJs(desc)+"')", null);
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
                if (!isHomePage && !urlInput.hasFocus()) {
                    String u = view.getUrl();
                    if (u != null) urlInput.setText(u);
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

    @SuppressLint("SetJavaScriptEnabled")
    private void setupDesktopWebView() {
        WebSettings s = desktopWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(DESKTOP_UA);
        desktopWebView.setBackgroundColor(Color.WHITE);
    }

    // ═══════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════

    private void navigateTo(String input) {
        String url = input.trim();
        if (url.isEmpty()) return;
        if (url.matches("\\d+"))                             url = "http://localhost:" + url;
        else if (url.matches("localhost(:\\d+)?(/.*)?"))     url = "http://" + url;
        else if (url.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) url = "http://" + url;
        else if (!url.startsWith("http://") && !url.startsWith("https://")
              && !url.startsWith("file://") && !url.startsWith("data:"))
            url = "http://" + url;
        isHomePage = false;
        urlInput.setText(url);
        browserView.loadUrl(url);
    }

    private void showHistory() {
        if (history.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Riwayat")
                .setMessage("Belum ada riwayat.").setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry e = history.get(i);
            items[i] = e.time + "  " + e.title + "\n" + e.url;
        }
        new AlertDialog.Builder(this)
            .setTitle("Riwayat (" + history.size() + ")")
            .setItems(items, (d, w) -> { navigateTo(history.get(w).url); hideKeyboard(); })
            .setNeutralButton("Hapus Semua", (d, w) -> history.clear())
            .setNegativeButton("Tutup", null).show();
    }

    private void toggleDevTools() {
        devToolsOpen = !devToolsOpen;
        devToolsPanel.setVisibility(devToolsOpen ? View.VISIBLE : View.GONE);
        if (devToolsOpen) devToolsView.evaluateJavascript("window.dtRefresh&&window.dtRefresh()", null);
    }

    private void updateNavBtns() {
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

    private TextView makeTagBtn(String text, String colorHex) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.parseColor(colorHex));
        btn.setTextSize(11f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding(dp(8), dp(7), dp(8), dp(7));
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#222639"));
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), Color.parseColor("#2e3347"));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(3), 0, 0, 0);
        btn.setLayoutParams(lp);
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
        if (!isHomePage && browserView.canGoBack()) { browserView.goBack(); return; }
        if (!isHomePage) { isHomePage = true; showSplash(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume()  { super.onResume();  browserView.onResume(); }
    @Override protected void onPause()   { super.onPause();   browserView.onPause(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        browserView.destroy();
        devToolsView.destroy();
        desktopWebView.destroy();
    }
}
