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
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    TextView btnBack, btnFwd, btnRefresh, btnDevTools, btnHistory, btnCookie;
    ProgressBar progressBar;
    boolean devToolsOpen = false;
    boolean isHomePage = true;
    boolean cookiesEnabled = true;

    // ── Quick-access ports ────────────────────────────────────────────────────
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
        "Django / Spring / Alt HTTP",
        "Various / RN Metro",
    };

    // ── History ───────────────────────────────────────────────────────────────
    static class HistoryEntry {
        String url, title, time;
        HistoryEntry(String u, String t, String ts) { url=u; title=t; time=ts; }
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
                    "window.dtConsole&&window.dtConsole('"+type+"','"+safe+"')", null);
            });
        }
        @JavascriptInterface
        public void logNetwork(String method, String url, String status, String duration, String type) {
            runOnUiThread(() -> {
                String su = url.replace("\\","\\\\").replace("'","\\'");
                devToolsView.evaluateJavascript(
                    "window.dtNetwork&&window.dtNetwork('"+method+"','"+su+
                    "','"+status+"','"+duration+"','"+type+"')", null);
            });
        }
        @JavascriptInterface
        public void evalInPage(String code) {
            runOnUiThread(() -> browserView.evaluateJavascript(code, value -> {
                if (value != null && !value.equals("null")) {
                    String safe = value.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
                    devToolsView.evaluateJavascript(
                        "window.dtConsole&&window.dtConsole('ret','"+safe+"')", null);
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
                        devToolsView.evaluateJavascript("window.dtStorage&&window.dtStorage("+value+")", null);
                }
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
                value -> {
                    if (value != null && !value.equals("null"))
                        devToolsView.evaluateJavascript("window.dtPerf&&window.dtPerf("+value+")", null);
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
                        devToolsView.evaluateJavascript("window.dtDom&&window.dtDom("+value+")", null);
                }
            ));
        }
    }

    // ── Inject script ─────────────────────────────────────────────────────────
    private static final String INJECT_SCRIPT =
        "(function(){if(window.__devInjected)return;window.__devInjected=true;" +
        "['log','warn','error','info','debug'].forEach(function(m){" +
        "var o=console[m].bind(console);console[m]=function(){" +
        "var msg=Array.from(arguments).map(function(a){" +
        "try{return typeof a==='object'?JSON.stringify(a):String(a);}catch(e){return String(a);}}).join(' ');" +
        "try{Android.logConsole(m==='debug'?'log':m,msg);}catch(e){}" +
        "return o.apply(console,arguments);};});" +
        "window.onerror=function(msg,src,line){try{Android.logConsole('error',msg+' ('+src+':'+line+')');}catch(e){}};"+
        "window.addEventListener('unhandledrejection',function(e){try{Android.logConsole('error','Uncaught Promise: '+e.reason);}catch(ex){}});"+
        "var _f=window.fetch;window.fetch=function(input,init){"+
        "var url=typeof input==='string'?input:(input&&input.url)||'?';"+
        "var method=(init&&init.method)||'GET';var t0=Date.now();"+
        "return _f.apply(this,arguments).then(function(r){"+
        "try{Android.logNetwork(method,url,String(r.status),String(Date.now()-t0)+'ms','fetch');}catch(e){}return r;"+
        "}).catch(function(e){try{Android.logNetwork(method,url,'ERR',String(Date.now()-t0)+'ms','fetch');}catch(ex){}throw e;});};"+
        "var _X=window.XMLHttpRequest;window.XMLHttpRequest=function(){"+
        "var x=new _X(),method='GET',url='',t0;"+
        "var _o=x.open.bind(x);x.open=function(m,u){method=m;url=u;t0=Date.now();return _o.apply(x,arguments);};"+
        "x.addEventListener('loadend',function(){try{Android.logNetwork(method,url,String(x.status),String(Date.now()-t0)+'ms','xhr');}catch(e){}});"+
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
        urlInput.setOnFocusChangeListener((v, focused) -> { if (focused) urlInput.selectAll(); });
        toolbar.addView(urlInput);

        btnRefresh = makeIconBtn("⟳");
        btnRefresh.setOnClickListener(v -> {
            if (isHomePage) return;
            if (browserView.getProgress() < 100) browserView.stopLoading();
            else browserView.reload();
        });
        toolbar.addView(btnRefresh);

        // Cookie button
        btnCookie = makeTagBtn("🍪", "#4ec9b0");
        btnCookie.setOnClickListener(v -> showCookieMenu());
        toolbar.addView(btnCookie);

        // History button
        btnHistory = makeIconBtn("☰");
        btnHistory.setOnClickListener(v -> showHistory());
        toolbar.addView(btnHistory);

        // DevTools button
        btnDevTools = makeTagBtn("DEV", "#4f9eff");
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

        View handle = new View(this);
        handle.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
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

        // Show splash
        isHomePage = true;
        showSplash();
    }

    // ── Splash page with port quick-launch grid ────────────────────────────────
    private void showSplash() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
          .append("<meta charset='UTF-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<style>")
          .append("*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}")
          .append("body{background:#0f1117;color:#d4d8f0;font-family:-apple-system,sans-serif;")
          .append("min-height:100vh;padding:24px 16px;overflow-y:auto}")
          .append(".hero{text-align:center;padding:28px 0 24px}")
          .append(".logo{font-size:52px;margin-bottom:10px}")
          .append(".title{font-size:22px;font-weight:700;color:#d4d8f0;margin-bottom:4px}")
          .append(".sub{font-size:13px;color:#6b7494;line-height:1.6}")
          .append(".section{margin-bottom:20px}")
          .append(".section-label{font-size:10px;color:#6b7494;text-transform:uppercase;")
          .append("letter-spacing:.8px;margin-bottom:8px;padding:0 2px}")
          .append(".port-row{display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap}")
          .append(".port-btn{flex:1;min-width:60px;background:#1a1d27;border:1.5px solid #2e3347;")
          .append("border-radius:10px;padding:12px 6px;text-align:center;cursor:pointer;")
          .append("transition:border-color .15s,background .15s;color:#d4d8f0}")
          .append(".port-btn:active{background:#222639;border-color:#4f9eff}")
          .append(".port-num{font-size:16px;font-weight:700;font-family:monospace;color:#4f9eff;margin-bottom:3px}")
          .append(".port-tag{font-size:10px;color:#6b7494;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
          .append(".divider{height:1px;background:#2e3347;margin:20px 0}")
          .append(".tip{background:#1a1d27;border:1px solid #2e3347;border-radius:10px;")
          .append("padding:14px;font-size:12px;color:#6b7494;line-height:1.7}")
          .append(".tip code{color:#4fcfff;font-family:monospace;background:#0f1117;")
          .append("padding:1px 5px;border-radius:4px}")
          .append("</style></head><body>")
          .append("<div class='hero'>")
          .append("<div class='logo'>⚡</div>")
          .append("<div class='title'>DevBrowser</div>")
          .append("<div class='sub'>Browser untuk debug &amp; preview proyek lokal</div>")
          .append("</div>");

        // Port groups
        String[][] portTags = {
            {"React","Next.js","Gatsby","CRA"},
            {"Angular","Astro","Vite prev","Custom"},
            {"Flask","Vue CLI","Vite","LiveServer"},
            {"Django","Spring","Apache","Alt"},
            {"Custom","Nodemon","Node Dbg","RN Metro"},
        };

        for (int g = 0; g < PORT_GROUPS.length; g++) {
            sb.append("<div class='section'>")
              .append("<div class='section-label'>").append(PORT_LABELS[g]).append("</div>")
              .append("<div class='port-row'>");
            for (int p = 0; p < PORT_GROUPS[g].length; p++) {
                int port = PORT_GROUPS[g][p];
                String tag = portTags[g][p];
                sb.append("<div class='port-btn' onclick='Android.openPort(").append(port).append(")'> ")
                  .append("<div class='port-num'>").append(port).append("</div>")
                  .append("<div class='port-tag'>").append(tag).append("</div>")
                  .append("</div>");
            }
            sb.append("</div></div>");
        }

        sb.append("<div class='divider'></div>")
          .append("<div class='tip'>")
          .append("Ketik langsung di address bar: ")
          .append("<code>3000</code> · <code>localhost:8080</code> · <code>192.168.x.x:5173</code>")
          .append("</div>")
          .append("<div style='height:16px'></div>")
          .append("</body></html>");

        browserView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void openPort(int port) {
                runOnUiThread(() -> navigateTo("localhost:" + port));
            }
        }, "Android");

        browserView.loadDataWithBaseURL(null, sb.toString(), "text/html", "utf-8", null);
    }

    // ── Cookie manager dialog ──────────────────────────────────────────────────
    private void showCookieMenu() {
        CookieManager cm = CookieManager.getInstance();
        String currentUrl = isHomePage ? null : browserView.getUrl();
        St
