package io.github.sagimor6.woltbillsplitter;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.HashMap;

public class CredFetcher extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_cred_fetcher);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

//        CookieManager.getInstance().removeAllCookies((val) -> {});

        WebView webView = (WebView)findViewById(R.id.webview);

        final String auth_type = getIntent().getStringExtra("auth_type");

        webView.getSettings().setJavaScriptEnabled(true);

        webView.getSettings().setUserAgentString(MyUtils.userAgent);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (auth_type.equals("wolt")) {
                    if (request.getUrl().toString().toLowerCase().startsWith("https://wolt.com/token-login?")) {
                        final String google_token = request.getUrl().getQueryParameter("google_token");

                        new Thread(() -> {
                            final String wolt_token;
                            try {
                                wolt_token = WoltUser.get_wolt_token_from_google_token(google_token);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            runOnUiThread(() -> {
                                Intent res = new Intent();
                                res.putExtra("wolt_token", wolt_token);

                                setResult(RESULT_OK, res);
                                finish();
                            });
                        }).start();

                        return true;
                    }
                } else {
                    if (request.getUrl().toString().toLowerCase().startsWith("https://github.com/sagimor6/yaya")) {
                        final String auth_code = request.getUrl().getQueryParameter("code");

                        new Thread(() -> {
                            HashMap<String, String> args = new HashMap<>();
                            args.put("grant_type", "authorization_code");
                            args.put("redirect_uri", "https://github.com/sagimor6/yaya");
                            args.put("client_id", "9w7YxUtYxN6uO4GRFjrVHzS0AZEAoNZPmCuDrCY6");
                            args.put("client_secret", getIntent().getStringExtra("split_client_secret"));
                            args.put("code", auth_code);

                            final String split_token;
                            try {
                                split_token = MyUtils.do_post("https://secure.splitwise.com/oauth/token", MyUtils.userAgent, args);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            runOnUiThread(() -> {
                                Intent res = new Intent();
                                res.putExtra("split_token", split_token);

                                setResult(RESULT_OK, res);
                                finish();
                            });
                        }).start();

                        return true;
                    }
                }
                return false;
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return super.shouldInterceptRequest(view, request);
            }
        });

        if (auth_type.equals("wolt")) {
            webView.loadUrl("https://authentication.wolt.com/v1/wauth2/consumer-google?audience=wolt-com");
        } else if (auth_type.equals("splitwise")) {
            webView.loadUrl("https://secure.splitwise.com/oauth/authorize?response_type=code&client_id=9w7YxUtYxN6uO4GRFjrVHzS0AZEAoNZPmCuDrCY6");
        } else {
            throw new RuntimeException("invalid auth_type");
        }
    }
}