package dev.cubefish.mobile.ai;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 方块鱼 AI 助手（手机版）界面。
 *
 * 界面是一张本地网页（assets/ai/index.html），模型对话与工具循环在网页里跑，
 * 需要「动手机/引擎」的能力通过 {@link AiBridge} 暴露给 JS（window.CubeFish.*）。
 */
public class AiChatActivity extends AppCompatActivity {
    private WebView webView;
    private AiBridge bridge;

    /** 打开时指定要显示的本地页面（默认聊天页，也可以传 guide.html 显示中文使用说明） */
    public static final String EXTRA_PAGE = "cubeFishPage";

    private static final int REQ_PICK_MODPACK = 4321;

    /** 让用户挑一个 .mrpack 文件，挑完在后台装 */
    public void pickModpackFile() {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_MODPACK);
        } catch (Throwable error) {
            publish("{\"type\":\"modpack\",\"stage\":\"error\",\"message\":\"无法打开文件选择器\"}");
        }
    }

    private void publish(String json) {
        if (bridge != null) bridge.publishEvent(json);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_MODPACK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            publish("{\"type\":\"modpack\",\"stage\":\"cancelled\"}");
            return;
        }
        final android.net.Uri uri = data.getData();
        publish("{\"type\":\"modpack\",\"stage\":\"copying\"}");
        new Thread(() -> {
            try {
                java.io.File temp = new java.io.File(getCacheDir(), "import.mrpack");
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                    if (in == null) throw new java.io.IOException("读不到文件");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                publish("{\"type\":\"modpack\",\"stage\":\"installing\"}");
                String summary = ModpackInstaller.install(temp, ModBrowser.target());
                publish("{\"type\":\"modpack\",\"stage\":\"done\",\"summary\":" + org.json.JSONObject.quote(summary) + "}");
            } catch (Throwable error) {
                publish("{\"type\":\"modpack\",\"stage\":\"error\",\"message\":" + org.json.JSONObject.quote(String.valueOf(error.getMessage())) + "}");
            }
        }).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0A0D12);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebViewClient(new WebViewClient());
        bridge = new AiBridge(this, webView);
        webView.addJavascriptInterface(bridge, "CubeFish");
        setContentView(
                webView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        String page = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null || page.isEmpty()) page = "index.html";
        webView.loadUrl("file:///android_asset/ai/" + page);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
