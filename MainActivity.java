package com.bondhuai.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.FirebaseMessaging;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String BASE_URL = "https://ais-pre-yiqoucgiqo3dvmkhsnkeme-570483827292.asia-northeast1.run.app";
    private static final String PREFS_NAME = "bondhu_native_prefs";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_NOTIF_ASKED = "notif_asked";
    private static final int REQ_NOTIF_PERM = 1002;
    private static final int REQ_MEDIA_PERMS = 1003;

    private static WeakReference<MainActivity> activeInstance = new WeakReference<>(null);
    private static volatile boolean isForeground = false;

    private WebView webView;
    private String currentFcmToken = null;
    private String pendingDeepLinkPath = null;
    private ValueCallback<Uri[]> fileUploadCallback = null;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    public static boolean isAppInForeground() {
        return isForeground;
    }

    public static void onTokenRefreshedFromService(String token) {
        if (token == null || token.trim().isEmpty()) return;
        MainActivity activity = activeInstance.get();
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(() -> activity.saveAndInjectFcmToken(token.trim()));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        activeInstance = new WeakReference<>(this);

        BondhuNotificationHelper.ensureChannels(this);
        currentFcmToken = getSavedFcmToken();
        pendingDeepLinkPath = extractTargetPathFromIntent(getIntent());

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (fileUploadCallback == null) return;
                    Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                    fileUploadCallback.onReceiveValue(uris);
                    fileUploadCallback = null;
                }
        );

        webView = findViewById(R.id.webview);
        setupWebView();
        requestInitialPermissions();
        fetchRealFcmToken();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        webView.loadUrl(buildLoadUrl(pendingDeepLinkPath));
        pendingDeepLinkPath = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        activeInstance = new WeakReference<>(this);
        if (currentFcmToken != null && !currentFcmToken.isEmpty()) {
            injectFcmTokenToWebView(currentFcmToken);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
    }

    @Override
    protected void onDestroy() {
        if (activeInstance.get() == this) {
            activeInstance = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String targetPath = extractTargetPathFromIntent(intent);
        if (targetPath != null && !targetPath.isEmpty() && webView != null) {
            webView.loadUrl(buildLoadUrl(targetPath));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new BondhuNativeBridge(), "BondhuNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    String host = uri.getHost() != null ? uri.getHost() : "";
                    if (host.contains("run.app") || host.contains("accounts.google.com") || host.contains("firebaseapp.com")) {
                        return false;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (currentFcmToken != null && !currentFcmToken.isEmpty()) {
                    injectFcmTokenToWebView(currentFcmToken);
                }
                dispatchPermissionEventToWebView(getNotificationPermissionString());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    ensureCameraAndMicPermissions();
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    filePickerLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });
    }

    private void fetchRealFcmToken() {
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) return;
                String token = task.getResult();
                if (token != null && !token.trim().isEmpty()) {
                    runOnUiThread(() -> saveAndInjectFcmToken(token.trim()));
                }
            });
        } catch (Exception ignored) {}
    }

    private void saveAndInjectFcmToken(String token) {
        currentFcmToken = token;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply();
        injectFcmTokenToWebView(token);
    }

    private String getSavedFcmToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_FCM_TOKEN, null);
    }

    private void injectFcmTokenToWebView(String token) {
        if (webView == null || token == null || token.isEmpty()) return;
        String safeToken = token.replace("\\", "\\\\").replace("'", "\\'");
        String js = "try{"
                + "localStorage.setItem('bondhu_native_fcm_token','" + safeToken + "');"
                + "if(window.__BONDHU_SET_FCM_TOKEN__){window.__BONDHU_SET_FCM_TOKEN__('" + safeToken + "');}"
                + "window.dispatchEvent(new CustomEvent('bondhu:fcm-token',{detail:'" + safeToken + "'}));"
                + "}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void dispatchPermissionEventToWebView(String perm) {
        if (webView == null) return;
        String js = "try{window.dispatchEvent(new CustomEvent('bondhu:notification-permission',{detail:'" + perm + "'}));}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private String getNotificationPermissionString() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "granted";
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return "granted";
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean asked = prefs.getBoolean(KEY_NOTIF_ASKED, false);
        if (asked && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            return "denied";
        }
        return "default";
    }

    private void requestInitialPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_MEDIA_PERMS);
        }
    }

    private void ensureCameraAndMicPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_MEDIA_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF_PERM || requestCode == REQ_MEDIA_PERMS) {
            String perm = getNotificationPermissionString();
            dispatchPermissionEventToWebView(perm);
            if ("granted".equals(perm) && currentFcmToken != null) {
                injectFcmTokenToWebView(currentFcmToken);
            }
        }
    }

    private String extractTargetPathFromIntent(Intent intent) {
        if (intent == null) return null;
        String directUrl = intent.getStringExtra("bondhu_target_url");
        if (directUrl != null && !directUrl.isEmpty()) return directUrl;

        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        String urlExtra = extras.getString("url");
        if (urlExtra != null && !urlExtra.isEmpty()) return urlExtra;

        String callId = extras.getString("callId");
        if (callId != null && !callId.isEmpty()) {
            return "/?callId=" + Uri.encode(callId) + "&callAction=open";
        }
        String groupId = extras.getString("groupId");
        if (groupId != null && !groupId.isEmpty()) {
            return "/?groupId=" + Uri.encode(groupId);
        }
        String chatWith = extras.getString("chatWith");
        if (chatWith == null || chatWith.isEmpty()) {
            chatWith = extras.getString("senderId");
        }
        if (chatWith != null && !chatWith.isEmpty()) {
            return "/?chatWith=" + Uri.encode(chatWith);
        }
        return null;
    }

    private String buildLoadUrl(String path) {
        if (path == null || path.isEmpty()) return BASE_URL;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("/")) return BASE_URL + path;
        return BASE_URL + "/" + path;
    }

    public class BondhuNativeBridge {
        @JavascriptInterface
        public String getFcmToken() {
            return currentFcmToken != null ? currentFcmToken : "";
        }

        @JavascriptInterface
        public String getNotificationPermission() {
            return getNotificationPermissionString();
        }

        @JavascriptInterface
        public boolean isNotificationPermissionGranted() {
            return "granted".equals(getNotificationPermissionString());
        }

        @JavascriptInterface
        public String requestNotificationPermission() {
            String current = getNotificationPermissionString();
            if ("granted".equals(current)) {
                return "granted";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
                runOnUiThread(() -> ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF_PERM
                ));
            }
            return current;
        }
    }
}
