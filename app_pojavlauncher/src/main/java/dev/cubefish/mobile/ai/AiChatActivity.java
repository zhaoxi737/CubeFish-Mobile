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
