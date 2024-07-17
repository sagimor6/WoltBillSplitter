package io.github.sagimor6.woltbillsplitter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.webkit.WebSettings;

import com.google.gson.GsonBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MyUtils {

    private static final BlockingQueue<Runnable> _IMAGE_LOADER_EXECUTOR_WORK_QUEUE = new LinkedBlockingQueue<>();
    public static final Executor IMAGE_LOADER_EXECUTOR = new ThreadPoolExecutor(20, 20, 1, TimeUnit.SECONDS, _IMAGE_LOADER_EXECUTOR_WORK_QUEUE);

    private static String url_encode(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] utf8_encode(String str) {
        try {
            return str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static JSONObject str_to_json(String str) {
        try {
            return new JSONObject(str);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static String do_http_get(String url_str) throws IOException {
        return MyUtils.do_http_req_url_encoded(url_str, "GET", MyUtils.userAgent, new HashMap<>(), new HashMap<>());
    }

    public static String do_http_req_url_encoded(String url_str, String method, String user_agent, Map<String, String> headers, Map<String, String> args) throws IOException {
        HttpResponse resp = do_http_req_url_encoded_raw(url_str, method, user_agent, headers, args);
        if (resp.code != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("got response code " + resp.code + "error: " + resp.content);
        }

        return resp.content;
    }

    public static HttpResponse do_http_req_url_encoded_raw(String url_str, String method, String user_agent, Map<String, String> headers, Map<String, String> args) throws IOException {
        String args_query_str;
        byte[] content = new byte[0];
        {
            String[] _content = new String[]{""};

            if(args != null) {
                args.forEach(
                        (name, val) -> {
                            if (!_content[0].isEmpty()) {
                                _content[0] += "&";
                            }
                            _content[0] += url_encode(name) + "=" + url_encode(val);
                        }
                );
            }
            args_query_str = _content[0];
        }

        if (method.equals("POST")) {
            if (!args_query_str.isEmpty()) {
                content = utf8_encode(args_query_str);
            }
            headers = new HashMap<String, String>(headers);
            headers.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        } else if (method.equals("GET")) {
            if (!args_query_str.isEmpty()) {
                url_str += "?" + args_query_str;
            }
        } else {
            throw new RuntimeException("method not GET/POST");
        }

        return do_http_req(url_str, method, user_agent, headers, content);
    }

    public static final class HttpResponse {
        public int code;
        public Map<String, List<String>> headers;
        public String content;

        public HttpResponse(int _code, Map<String, List<String>> _headers, String _content) {
            code = _code;
            headers = _headers;
            content = _content;
        }
    }

    public static HttpResponse do_http_req(String url_str, String method, String user_agent, Map<String, String> headers, byte[] content) throws IOException {
        URL url;
        try {
            url = new URL(url_str);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        HttpURLConnection con;
        con = (HttpURLConnection) url.openConnection();

        try {
            con.setRequestMethod(method);
        } catch (ProtocolException e) {
            throw new RuntimeException(e);
        }

        con.setRequestProperty("User-Agent", user_agent);
        con.setDoInput(true);

        con.setInstanceFollowRedirects(false);

        con.setRequestProperty("Content-Length", "" + content.length);
        con.setFixedLengthStreamingMode(content.length);
        if (content.length != 0) {
            con.setDoOutput(true);
        }

        //con.getHeaderFields()

        {
            final HttpURLConnection con2 = con;
            headers.forEach((name, val) -> {
                con2.setRequestProperty(name, val);
            });
        }

        con.setConnectTimeout(3000);
        con.setReadTimeout(2000);

        con.connect();
        if (content.length != 0) {
            OutputStream con_os = con.getOutputStream();
            con_os.write(content);
            con_os.flush();
            con_os.close();
        }

        int resp_code = con.getResponseCode();

        byte[] buf = new byte[1024];
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        InputStream s;
        if (resp_code < 400) {
            s = con.getInputStream();
        } else {
            s = con.getErrorStream();
        }

        while (true) {
            int num_read = s.read(buf);
            if (num_read < 0) {
                break;
            }
            os.write(buf, 0, num_read);
        }

        String result;
        try {
            result = os.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        return new HttpResponse(resp_code, con.getHeaderFields(), result);
    }

    public static String do_post(String url_str, String user_agent, Map<String, String> args) throws IOException {
        return do_http_req_url_encoded(url_str, "POST", user_agent, new HashMap<>(), args);
    }

    public static String do_bearer_get(String url_str, String bearer, Map<String, String> args) throws IOException {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + bearer);
        return do_http_req_url_encoded(url_str, "GET", userAgent, headers, args);
    }

    public static String userAgent;


    public static byte[] do_hmac(byte[] msg, byte[] key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(msg);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] do_aes_cbc(byte[] msg, byte[] key, byte[] iv, boolean encrypt) {
        SecretKeySpec _key = new SecretKeySpec(key, "AES");
        IvParameterSpec _iv = new IvParameterSpec(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, _key, _iv);
            return cipher.doFinal(msg);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException |
                 BadPaddingException | InvalidAlgorithmParameterException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] do_random(int size) {
        byte[] res = new byte[size];
        new SecureRandom().nextBytes(res);
        return res;
    }

    public static byte[] do_sha256(byte[] msg) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(msg);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static final int AES_BLOCK_SIZE = 16;
    public static final int AES_KEY_SIZE = 32; // aes-256
    public static final int SHA256_DIGEST_SIZE = 32;

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

    public static byte[] concat_bytes(byte[] arr1, byte[] arr2) {
        byte[] res = new byte[arr1.length + arr2.length];
        System.arraycopy(arr1, 0, res, 0, arr1.length);
        System.arraycopy(arr2, 0, res, arr1.length, arr2.length);
        return res;
    }

    public static String exception_to_string(Throwable e) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        e.printStackTrace(printWriter);
        return stringWriter.toString();
    }

    private static volatile AppCtx app_ctx = null;
    private static final Object app_ctx_lock = new Object();

    public static AppCtx get_app_ctx(Context ctx) {
        AppCtx _app_ctx = app_ctx;
        if (_app_ctx != null) {
            return _app_ctx;
        }

        synchronized (app_ctx_lock) {
            _app_ctx = app_ctx;
            if (_app_ctx != null) {
                return _app_ctx;
            }
            String app_ctx_json_str = ctx.getApplicationContext().getSharedPreferences("save", Context.MODE_PRIVATE).getString("app_ctx_json", null);
            _app_ctx = new GsonBuilder().create().fromJson(app_ctx_json_str, AppCtx.class);
            if (_app_ctx == null) {
                _app_ctx = new AppCtx();
            }

            _app_ctx.on_save_state_cb = appCtx -> {
                ctx.getApplicationContext().getSharedPreferences("save", Context.MODE_PRIVATE).edit().putString("app_ctx_json", new GsonBuilder().create().toJson(appCtx)).commit();
            };

            _app_ctx.fill_transient_state();
            app_ctx = _app_ctx;
        }

        return _app_ctx;
    }

    public static void delete_app_ctx(Context ctx) {
        synchronized (app_ctx_lock) {
            AppCtx _app_ctx = app_ctx;
            if (_app_ctx == null) {
                return;
            }
            _app_ctx.on_save_state_cb = appCtx -> {};
            ctx.getApplicationContext().getSharedPreferences("save", Context.MODE_PRIVATE).edit().remove("app_ctx_json").commit();
            app_ctx = null;
        }
    }

    public static int compare_versions(String ver1, String ver2) throws NumberFormatException {
        String[] ver1_parts = ver1.split("\\.");
        String[] ver2_parts = ver2.split("\\.");

        int max_len = Math.max(ver1_parts.length, ver2_parts.length);

        for (int i = 0; i < max_len; i++) {
            int part1;
            int part2;
            if (i < ver1_parts.length) {
                part1 = Integer.parseInt(ver1_parts[i], 10);
                if (part1 < 0) {
                    throw new NumberFormatException();
                }
            } else {
                part1 = 0;
            }
            if (i < ver2_parts.length) {
                part2 = Integer.parseInt(ver2_parts[i], 10);
                if (part2 < 0) {
                    throw new NumberFormatException();
                }
            } else {
                part2 = 0;
            }
            if (part1 > part2) {
                return 1;
            } else if (part1 < part2) {
                return -1;
            }
        }

        return 0;
    }

    public static String my_package_name;
    public static RSAPublicKey my_package_pub_key;
    public static Certificate my_package_cert;
    public static String my_package_version;

    public static String normalize_android_user_agent(String userAgent) {
        // remove any webview and emulator traces
        Pattern p = Pattern.compile("^([^()\\s]*\\s\\([^();]*;\\s[^();]*;\\s)([^()]*)\\)");
        Matcher m = p.matcher(userAgent);
        return m.replaceFirst("$1K)");
    }

    public static void fill_static_info(Context ctx) {
        ctx = ctx.getApplicationContext();
        my_package_name = ctx.getPackageName();
        try {
            int flags;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                flags = PackageManager.GET_SIGNING_CERTIFICATES;
            } else {
                flags = PackageManager.GET_SIGNATURES;
            }
            PackageInfo my_pkg_info = ctx.getPackageManager().getPackageInfo(my_package_name, flags);
            Signature my_sig;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                my_sig = my_pkg_info.signingInfo.getApkContentsSigners()[0];
            } else {
                my_sig = my_pkg_info.signatures[0];
            }
            my_package_cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(my_sig.toByteArray()));
            my_package_pub_key = (RSAPublicKey) my_package_cert.getPublicKey();
            my_package_version = my_pkg_info.versionName;
        } catch (PackageManager.NameNotFoundException | CertificateException e) {
            MyLogger.e(e);
            throw new RuntimeException(e);
        }

        userAgent = normalize_android_user_agent(WebSettings.getDefaultUserAgent(ctx));
    }

}
