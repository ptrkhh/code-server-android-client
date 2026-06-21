package com.term.codeserver;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private TextView splash, reconnect;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE = 1;
    private static final String URL = "http://localhost:8081";

    // #B per-window URL: the folder this window is on, survives reload/recreation
    private String currentUrl = URL;

    // theme-aware colors (VS Code dark/light palette)
    private int bg, text, accent;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg     = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        text   = dark ? 0xFFCCCCCC : 0xFF333333;
        accent = dark ? 0xFF1F9CF0 : 0xFF0065A9;

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // #1 blob-download bridge
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveBlob(String dataUrl, String name) {
                runOnUiThread(new SaveBlob(dataUrl, name));
            }
        }, "Android");

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        // #4 file download for http(s) URLs -> Android DownloadManager
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd,
                                        String mime, long len) {
                if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
                    return; // handled by the blob bridge (#1)
                }
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String n = URLUtil.guessFileName(url, cd, mime);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, n);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            }
        });

        web.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { return true; }
        });
        web.setLongClickable(false);

        // #9 splash overlay (kills white flash, theme-matched)
        splash = overlay("VS Code", accent, false);
        // #3 reconnect overlay (hidden until a load error)
        reconnect = overlay("⚡  Disconnected — tap to reconnect", text, true);
        reconnect.setVisibility(View.GONE);
        reconnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reconnect.setVisibility(View.GONE);
                splash.setVisibility(View.VISIBLE);
                web.reload();
            }
        });

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(web, mp);
        root.addView(splash, mp);
        root.addView(reconnect, mp);
        setContentView(root);

        // #C keep the process alive while backgrounded -> no cold reload on app-switch
        startService(new Intent(this, KeepAliveService.class));

        // #B restore this window's own page instead of always reloading the bare URL.
        // Bare-URL reload is what made two windows converge onto code-server's shared
        // "last opened folder". restoreState replays this window's history (its ?folder=).
        if (b != null && web.restoreState(b) != null) {
            // restored from saved history
        } else if (b != null && b.getString("url") != null) {
            web.loadUrl(b.getString("url"));
        } else {
            web.loadUrl(URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        out.putString("url", currentUrl);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    private TextView overlay(String msg, int color, boolean clickable) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(color);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(bg);
        t.setClickable(clickable);
        return t;
    }

    // #7 keep localhost in-app, send other hosts to the system browser
    private boolean external(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null || h.equals("localhost") || h.equals("127.0.0.1")) return false;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (Exception e) { return false; }
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            return external(req.getUrl().toString());
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return external(url);
        }
        @Override
        public void onPageFinished(WebView v, String url) {
            if (url != null && !url.startsWith("data:")) currentUrl = url;
            splash.setVisibility(View.GONE);
            reconnect.setVisibility(View.GONE);
            v.evaluateJavascript(BLOB_HOOK, null);
        }
        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            if (url != null && url.startsWith("http")) currentUrl = url;
        }
        @Override
        public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) {
                splash.setVisibility(View.GONE);
                reconnect.setVisibility(View.VISIBLE);
            }
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = cb;
            try { startActivityForResult(params.createIntent(), REQ_FILE); }
            catch (Exception e) { filePathCallback = null; return false; }
            return true;
        }
        @Override
        public void onPermissionRequest(final PermissionRequest req) {
            runOnUiThread(new Runnable() {
                public void run() { req.grant(req.getResources()); }
            });
        }
    }

    // writes a data: URL (base64) captured from a blob download to public Downloads
    private class SaveBlob implements Runnable {
        private final String dataUrl, name;
        SaveBlob(String d, String n) { dataUrl = d; name = n; }
        public void run() {
            try {
                int c = dataUrl.indexOf(',');
                byte[] data = Base64.decode(dataUrl.substring(c + 1), Base64.DEFAULT);
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, (name == null || name.isEmpty()) ? "download" : name);
                FileOutputStream o = new FileOutputStream(f);
                o.write(data); o.close();
                Toast.makeText(MainActivity.this, "Saved " + f.getName(),
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // intercepts blob-URL anchor clicks, ships bytes to Android.saveBlob (#1)
    private static final String BLOB_HOOK =
        "(function(){if(window.__blobHook)return;window.__blobHook=1;" +
        "var c=HTMLElement.prototype.click;HTMLElement.prototype.click=function(){" +
        "try{if(this.tagName==='A'&&this.href&&this.href.indexOf('blob:')===0){" +
        "var n=this.getAttribute('download')||'download';" +
        "var x=new XMLHttpRequest();x.open('GET',this.href,true);x.responseType='blob';" +
        "x.onload=function(){var fr=new FileReader();" +
        "fr.onload=function(){Android.saveBlob(fr.result,n);};" +
        "fr.readAsDataURL(x.response);};x.send();return;}}catch(e){}" +
        "return c.apply(this,arguments);};})();";

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript(
                "(function(){var t=document.activeElement||document.body;" +
                "t.dispatchEvent(new KeyboardEvent('keydown'," +
                "{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));})();",
                null);
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
