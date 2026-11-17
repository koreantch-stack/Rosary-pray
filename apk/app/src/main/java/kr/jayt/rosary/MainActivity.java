package kr.jayt.rosary;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 얇은 껍데기 앱. 화면과 기능은 GitHub Pages 주소의 웹앱이 맡고,
 * 이 앱은 아이콘, 화면 켜 두기, 상태 표시줄 색, 새 앱 설치만 담당한다.
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://koreantch-stack.github.io/Rosary-pray/";
    private static final String RELEASE_PREFIX = "https://github.com/koreantch-stack/rosary-pray/releases/download/";

    private WebView web;

    /** 상태 표시줄과 아래 막대 색을 웹앱의 밝은/어두운 화면에 맞춘다. */
    private void applyBars(boolean dark) {
        int c = dark ? 0xFF15110E : 0xFFFBF8F2;
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) light |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (dark) f &= ~light; else f |= light;
        d.setSystemUiVisibility(f);
        if (web != null) web.setBackgroundColor(c);
    }

    /** 내려받은 새 앱을 설치 화면으로 넘긴다. "이 앱에서 설치 허용"이 꺼져 있으면 그 설정 화면부터 연다. */
    private void launchInstaller() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "설치를 허용해 주세요. 허용한 뒤 뒤로 가서 업데이트를 다시 눌러 주세요", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                web.evaluateJavascript("window.onUpdateFail&&window.onUpdateFail(true)", null);
                return;
            }
            Uri u = Uri.parse("content://" + UpdateProvider.AUTH + "/Rosary.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "설치 화면을 열지 못했습니다", Toast.LENGTH_LONG).show();
            web.evaluateJavascript("window.onUpdateFail&&window.onUpdateFail()", null);
        }
    }

    /** 웹앱에서 window.AppNative 로 보이는 통로 */
    private class Bridge {
        /** 지금 설치된 앱의 빌드 번호 (새 앱이 있는지 비교하는 데 씀) */
        @JavascriptInterface
        public int versionCode() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        /** 기도하는 동안 화면이 꺼지지 않게 한다. */
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setTheme(final boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }

        /** 기록 글자를 카카오톡 등으로 보내는 공유 창 */
        @JavascriptInterface
        public void shareText(final String text) {
            if (text == null || text.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "묵주기도 기록 보내기"));
                } catch (Exception ignored) {
                    // 공유할 앱이 없으면 무시
                }
            });
        }

        /** 새 앱을 내려받아 설치 화면을 띄운다. 이 저장소의 릴리스 주소만 받는다. */
        @JavascriptInterface
        public void installUpdate(final String url) {
            if (url == null || !url.toLowerCase().startsWith(RELEASE_PREFIX)) return;
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    int total = c.getContentLength();
                    File f = UpdateProvider.apkFile(MainActivity.this);
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        long got = 0;
                        int last = -1, n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            got += n;
                            int pct = total > 0 ? (int) (got * 100 / total) : -1;
                            if (pct != last) {
                                last = pct;
                                final int pp = pct;
                                runOnUiThread(() -> web.evaluateJavascript("window.onUpdateProgress&&window.onUpdateProgress(" + pp + ")", null));
                            }
                        }
                    }
                    runOnUiThread(() -> launchInstaller());
                } catch (Exception e) {
                    runOnUiThread(() -> web.evaluateJavascript("window.onUpdateFail&&window.onUpdateFail()", null));
                }
            }).start();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        applyBars(false);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AppNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u.toString().startsWith(BASE)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                    // 열 앱이 없으면 무시
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                String html = "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<body style='font-family:sans-serif;background:#fbf8f2;color:#2e2721;padding:32px;text-align:center'>"
                        + "<h2>인터넷에 연결되어 있지 않습니다</h2>"
                        + "<p>처음 한 번은 인터넷이 필요합니다.<br>한 번 열어 본 뒤에는 인터넷 없이도 열립니다.</p>"
                        + "<button style='font-size:20px;padding:16px 32px;border:0;border-radius:12px;background:#e0a43a;color:#2e2721'"
                        + " onclick=\"location.href='" + BASE + "'\">다시 시도</button></body>";
                view.loadData(html, "text/html; charset=utf-8", null);
            }
        });

        web.loadUrl(BASE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.onNativeBack&&window.onNativeBack())?'1':'0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
