package com.devbrowser.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    WebView browserView;
    WebView devToolsView;
    LinearLayout devToolsPanel;
    EditText urlInput;
    TextView btnBack, btnFwd, btnRefresh, btnDevTools;
    ProgressBar progressBar;
    boolean devToolsOpen = false;
    String currentUrl = "";

    // ── JavaScript Bridge ──────────────────────────────────────────────────────
    public class DevBridge {

        @JavascriptInterface
        public void logConsole(String type, String message) {
            runOnUiThread(() -> {
                String safe = message.replace("\\", "\\\\")
                                     .replace("'", "\\'")
                                     .replace("\n", "\\n")
                                     .replace("\r", "");
                devToolsView.evaluateJavascript(
                    "window.dtConsole && window.dtConsole('" + type + "','" + safe + "')", null);
            });
        }

        @JavascriptInterface
        public void logNetwork(String method, String url, String status, String duration, String type) {
            runOnUiThread(() -> {
                String su  = url.replace("\\","\\\\").replace("'","\\'");
                devToolsView.evaluateJavascript(
                    "window.dtNetwork && window.dtNetwork('" + method + "','" + su +
                    "','" + status + "','" + duration + "','" + type + "')", null);
            });
        }

        @JavascriptInterface
        public void evalResult(String result) {
            runOnUiThread(() -> {
                String safe = result.replace("\\", "\\\\").replace("'", "\\'").replace("\n","\\n");
                devToolsView.evaluateJavascript(
                    "window.dtConsole && window.dtConsole('ret','" + safe + "')", null);
            });
        }

        @JavascriptInterface
        public void evalInPage(String code) {
            runOnUiThread(() -> browserView.evaluateJavascript(code, value -> {
                if (value != null && !value.equals("null")) {
                    String safe = value.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
                    devToolsView.evaluateJavascript(
                        "window.dtConsole && window.dtConsole('ret','" + safe + "')", null);
                }
            }));
        }

        @JavascriptInterface
        public void refreshStorage() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function(){" +
                "  var ls={},ss={},ck=document.cookie;" +
                "  for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);ls[k]=localStorage.getItem(k);}" +
                "  for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);ss[k]=sessionStorage.getItem(k);}" +
                "  return JSON.stringify({ls:ls,ss:ss,ck:ck});" +
                "})()",
                value -> {
                    if (value != null && !value.equals("null")) {
                        String safe = value.replace("\\","\\\\").replace("'","\\'").replace("\n","");
                        devToolsView.evaluateJavascript(
                            "window.dtStorage && window.dtStorage(" + value + ")", null);
                    }
                }
            ));
        }

        @JavascriptInterface
        public void getPerf() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function(){" +
                "  var n=performance.getEntriesByType('navigation')[0]||{};" +
                "  var r=performance.getEntriesByType('resource');" +
                "  return JSON.stringify({" +
                "    dns: Math.round((n.domainLookupEnd||0)-(n.domainLookupStart||0))," +
                "    tcp: Math.round((n.connectEnd||0)-(n.connectStart||0))," +
                "    ttfb:Math.round((n.responseStart||0)-(n.fetchStart||0))," +
                "    dom: Math.round((n.domContentLoadedEventEnd||0)-(n.fetchStart||0))," +
                "    load:Math.round((n.loadEventEnd||0)-(n.fetchStart||0))," +
                "    res: r.length," +
                "    resources: r.slice(0,12).map(function(x){return{name:x.name.split('/').pop().split('?')[0].substring(0,24)||'?',dur:Math.round(x.duration),size:Math.round(x.encodedBodySize/1024)||0};})" +
                "  });" +
                "})()",
                value -> {
                    if (value != null && !value.equals("null")) {
                        devToolsView.evaluateJavascript(
                            "window.dtPerf && window.dtPerf(" + value + ")", null);
                    }
                }
            ));
        }

        @JavascriptInterface
        public void getDom() {
            runOnUiThread(() -> browserView.evaluateJavascript(
                "(function serializeNode(n,d){" +
                "  if(d>5)return null;" +
                "  if(n.nodeType===3){var t=n.textContent.trim();return t?{type:'text',text:t.substring(0,60)}:null;}" +
                "  if(n.nodeType!==1)return null;" +
                "  var attrs={};" +
                "  for(var i=0;i<(n.attributes||[]).length;i++){attrs[n.attributes[i].name]=n.attributes[i].value.substring(0,50);}" +
                "  var kids=[];" +
                "  for(var i=0;i<n.childNodes.length;i++){var c=serializeNode(n.childNodes[i],d+1);if(c)kids.push(c);}" +
                "  return{type:'elem',tag:n.tagName.toLowerCase(),attrs:attrs,children:kids};" +
                "})(document.documentElement,0)",
                value -> {
                    if (value != null && !value.equals("null")) {
                        devToolsView.evaluateJavascript(
                            "window.dtDom && window.dtDom(" + value + ")", null);
                    }
                }
            ));
        }
    }

    // ── Injected script for intercepting console & network ────────────────────
    private static final String INJECT_SCRIPT =
        "(function(){\n" +
        "  if(window.__devInjected)return;\n" +
        "  window.__devInjected=true;\n" +
        "  ['log','warn','error','info','debug'].forEach(function(m){\n" +
        "    var o=console[m].bind(console);\n" +
        "    console[m]=function(){\n" +
        "      var msg=Array.from(arguments).map(function(a){\n" +
        "        try{return typeof a==='object'?JSON.stringify(a):String(a);}catch(e){return String(a);}\n" +
        "      }).join(' ');\n" +
        "      try{Android.logConsole(m==='debug'?'log':m,msg);}catch(e){}\n" +
        "      return o.apply(console,arguments);\n" +
        "    };\n" +
        "  });\n" +
        "  window.onerror=function(msg,src,line){\n" +
        "    try{Android.logConsole('error',msg+' ('+src+':'+line+')');}catch(e){}\n" +
        "  };\n" +
        "  var _fetch=window.fetch;\n" +
        "  window.fetch=function(input,init){\n" +
        "    var url=typeof input==='string'?input:(input.url||'?');\n" +
        "    var method=(init&&init.method)||'GET';\n" +
        "    var t0=Date.now();\n" +
        "    return _fetch.apply(this,arguments).then(function(r){\n" +
        "      try{Android.logNetwork(method,url,String(r.status),String(Date.now()-t0)+'ms','fetch');}catch(e){}\n" +
        "      return r;\n" +
        "    }).catch(function(e){\n" +
        "      try{Android.logNetwork(method,url,'ERR',String(Date.now()-t0)+'ms','fetch');}catch(ex){}\n" +
        "      throw e;\n" +
        "    });\n" +
        "  };\n" +
        "  var _XHR=window.XMLHttpRequest;\n" +
        "  window.XMLHttpRequest=function(){\n" +
        "    var x=new _XHR(),method='GET',url='',t0;\n" +
        "    var _open=x.open.bind(x);\n" +
        "    x.open=function(m,u){method=m;url=u;t0=Date.now();return _open.apply(x,arguments);};\n" +
        "    x.addEventListener('loadend',function(){\n" +
        "      try{Android.logNetwork(method,url,String(x.status),String(Date.now()-t0)+'ms','xhr');}catch(e){}\n" +
        "    });\n" +
        "    return x;\n" +
        "  };\n" +
        "})();";

    // ── Build UI ──────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        // ── Root layout ──
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f1117"));

        // ── Toolbar ──
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(Color.parseColor("#1a1d27"));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout.LayoutParams toolbarParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        toolbar.setLayoutParams(toolbarParams);

        // Back button
        btnBack = makeBtn("←");
        btnBack.setOnClickListener(v -> { if (browserView.canGoBack()) browserView.goBack(); });
        toolbar.addView(btnBack);

        // Forward button
        btnFwd = makeBtn("→");
        btnFwd.setEnabled(false);
        btnFwd.setOnClickListener(v -> { if (browserView.canGoForward()) browserView.goForward(); });
        toolbar.addView(btnFwd);

        // URL / port input
        urlInput = new EditText(this);
        urlInput.setHint("localhost:3000  atau  URL lengkap");
        urlInput.setHintTextColor(Color.parseColor("#6b7494"));
        urlInput.setTextColor(Color.parseColor("#d4d8f0"));
        urlInput.setBackgroundResource(android.R.drawable.editbox_background_normal);
        urlInput.setBackground(null);
        urlInput.setPadding(dp(10), dp(4), dp(10), dp(4));
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextSize(13f);

        // Draw URL bar background
        android.graphics.drawable.GradientDrawable urlBg = new android.graphics.drawable.GradientDrawable();
        urlBg.setColor(Color.parseColor("#0f1117"));
        urlBg.setCornerRadius(dp(16));
        urlBg.setStroke(dp(1), Color.parseColor("#2e3347"));
        urlInput.setBackground(urlBg);

        LinearLayout.LayoutParams urlParams =
            new LinearLayout.LayoutParams(0, dp(36), 1f);
        urlParams.setMargins(dp(4), 0, dp(4), 0);
        urlInput.setLayoutParams(urlParams);

        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(urlInput.getText().toString());
                urlInput.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
        toolbar.addView(urlInput);

        // Refresh
        btnRefresh = makeBtn("⟳");
        btnRefresh.setOnClickListener(v -> {
            if (browserView.getProgress() < 100) browserView.stopLoading();
            else browserView.reload();
        });
        toolbar.addView(btnRefresh);

        // DevTools toggle
        btnDevTools = new TextView(this);
        btnDevTools.setText("DEV");
        btnDevTools.setTextColor(Color.parseColor("#4f9eff"));
        btnDevTools.setTextSize(11f);
        btnDevTools.setTypeface(null, android.graphics.Typeface.BOLD);
        btnDevTools.setPadding(dp(10), dp(8), dp(10), dp(8));

        android.graphics.drawable.GradientDrawable devBg = new android.graphics.drawable.GradientDrawable();
        devBg.setColor(Color.parseColor("#222639"));
        devBg.setCornerRadius(dp(7));
        devBg.setStroke(dp(1), Color.parseColor("#2e3347"));
        btnDevTools.setBackground(devBg);
        btnDevTools.setOnClickListener(v -> toggleDevTools());
        toolbar.addView(btnDevTools);

        root.addView(toolbar);

        // ── Progress bar ──
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        try {
            progressBar.getProgressDrawable().setColorFilter(
                Color.parseColor("#4f9eff"),
                android.graphics.PorterDuff.Mode.SRC_IN
            );
        } catch (Exception ignored) {}
        root.addView(progressBar);

        // ── Browser WebView ──
        browserView = new WebView(this);
        LinearLayout.LayoutParams browserParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        browserView.setLayoutParams(browserParams);
        setupBrowserView();
        root.addView(browserView);

        // ── DevTools panel ──
        devToolsPanel = new LinearLayout(this);
        devToolsPanel.setOrientation(LinearLayout.VERTICAL);
        devToolsPanel.setBackgroundColor(Color.parseColor("#1a1d27"));
        LinearLayout.LayoutParams dtParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
        devToolsPanel.setLayoutParams(dtParams);
        devToolsPanel.setVisibility(View.GONE);

        // Drag handle
        View handle = new View(this);
        handle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        handle.setBackgroundColor(Color.TRANSPARENT);

        // Handle touch for resize
        final int[] dragY = {0};
        final int[] dragH = {dp(320)};
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragY[0] = (int) ev.getRawY();
                    dragH[0] = devToolsPanel.getLayoutParams().height;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    int dy = dragY[0] - (int) ev.getRawY();
                    int newH = Math.max(dp(120), Math.min((int)(getResources().getDisplayMetrics().heightPixels * 0.7f), dragH[0] + dy));
                    ViewGroup.LayoutParams lp = devToolsPanel.getLayoutParams();
                    lp.height = newH;
                    devToolsPanel.setLayoutParams(lp);
                    break;
            }
            return true;
        });
        devToolsPanel.addView(handle);

        // DevTools WebView
        devToolsView = new WebView(this);
        devToolsView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupDevToolsView();
        devToolsPanel.addView(devToolsView);

        root.addView(devToolsPanel);

        setContentView(root);

        // Load devtools
        devToolsView.loadUrl("file:///android_asset/devtools.html");

        // Start with empty page
        browserView.loadData(
            "<html><body style='background:#0f1117;color:#6b7494;font-family:sans-serif;" +
            "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:20px'>" +
            "<div style='font-size:48px;margin-bottom:16px'>⚡</div>" +
            "<div style='font-size:18px;color:#d4d8f0;margin-bottom:8px'>DevBrowser</div>" +
            "<div style='font-size:13px;line-height:1.7'>Masukkan port proyek kamu<br>" +
            "<span style='color:#4f9eff;font-family:monospace'>localhost:3000</span> " +
            "atau URL lengkap<br>di address bar atas</div></body></html>",
            "text/html", "utf-8"
        );
    }

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
                String url = req.getUrl().toString();
                urlInput.setText(url);
                currentUrl = url;
                return false;
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(10);
                urlInput.setText(url);
                currentUrl = url;
                runOnUiThread(() -> {
                    btnBack.setAlpha(view.canGoBack() ? 1f : 0.3f);
                    btnFwd.setAlpha(view.canGoForward() ? 1f : 0.3f);
                });
                devToolsView.evaluateJavascript(
                    "window.dtLog && window.dtLog('Navigating to: " + url + "')", null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.INVISIBLE);
                progressBar.setProgress(100);
                urlInput.setText(url);
                currentUrl = url;
                runOnUiThread(() -> {
                    btnBack.setAlpha(view.canGoBack() ? 1f : 0.3f);
                    btnFwd.setAlpha(view.canGoForward() ? 1f : 0.3f);
                });
                // Inject interceptors
                view.evaluateJavascript(INJECT_SCRIPT, null);
                devToolsView.evaluateJavascript(
                    "window.dtLog && window.dtLog('Page loaded: " + url + "')", null);
            }
        });

        browserView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                if (progress == 100) progressBar.setVisibility(View.INVISIBLE);
                else progressBar.setVisibility(View.VISIBLE);
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                // Also captured by injected JS
                return false;
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

    private void navigateTo(String input) {
        String url = input.trim();
        if (url.isEmpty()) return;
        // If just a number, treat as port
        if (url.matches("\\d+")) {
            url = "http://localhost:" + url;
        } else if (url.matches("localhost:\\d+.*")) {
            url = "http://" + url;
        } else if (url.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
            url = "http://" + url;
        } else if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("file://") && !url.startsWith("data:")) {
            url = "http://" + url;
        }
        currentUrl = url;
        urlInput.setText(url);
        browserView.loadUrl(url);
    }

    private void toggleDevTools() {
        devToolsOpen = !devToolsOpen;
        devToolsPanel.setVisibility(devToolsOpen ? View.VISIBLE : View.GONE);
        if (devToolsOpen) {
            devToolsView.evaluateJavascript("window.dtRefresh && window.dtRefresh()", null);
        }
    }

    private TextView makeBtn(String text) {
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
