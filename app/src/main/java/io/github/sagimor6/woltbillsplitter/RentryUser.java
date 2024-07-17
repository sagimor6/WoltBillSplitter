package io.github.sagimor6.woltbillsplitter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

public class RentryUser {
    public String csrf_token;

    public transient Consumer<RentryUser> on_save_cb;

    public RentryUser() {}

    public RentryUser(String _csrf_token) {
        csrf_token = _csrf_token;
    }

    public static String create_new_csrf_token() throws IOException {
        final String url = "https://rentry.co/";
        MyUtils.HttpResponse resp = MyUtils.do_http_req(url, "GET", MyUtils.userAgent, new HashMap<>(), new byte[]{});
        CookieManager cookieManager = new CookieManager();

        try {
            cookieManager.put(new URI(url), resp.headers);
            for(HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
                if (cookie.getName().equals("csrftoken")) {
                    return cookie.getValue();
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }

        throw new RuntimeException("no csrf token");
    }

    public void create_new_csrf_token_on_demand() throws IOException {
        if (csrf_token == null) {
            csrf_token = create_new_csrf_token();
            if (on_save_cb != null) {
                on_save_cb.accept(this);
            }
        }
    }

    public void create_rentry(String url, String edit_code, String text) throws JSONException, IOException, RentryAlreadyExistsException {
        create_new_csrf_token_on_demand();

        HashMap<String, String> args = new HashMap<>();
        args.put("csrfmiddlewaretoken", csrf_token);
        args.put("url", url);
        args.put("edit_code", edit_code);
        args.put("text", text);

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Cookie", "csrftoken=" + csrf_token);
        headers.put("Referer", "https://rentry.co/");
        JSONObject res = new JSONObject(MyUtils.do_http_req_url_encoded("https://rentry.co/api/new", "POST", MyUtils.userAgent, headers, args));
        if (res.getInt("status") == 200) {
            return;
        }

        String errors = res.optString("errors", "--no content--");
        if (res.getInt("status") == 400 && errors.equals("Entry with this url already exists.")) {
            throw new RentryAlreadyExistsException();
        }

        throw new RuntimeException("rentry create failed: " + errors);
    }

    public void edit_rentry(String url, String edit_code, String text) throws RentryDoesntExistException, JSONException, IOException {
        create_new_csrf_token_on_demand();

        HashMap<String, String> args = new HashMap<>();
        args.put("csrfmiddlewaretoken", csrf_token);
        args.put("edit_code", edit_code);
        args.put("text", text);

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Cookie", "csrftoken=" + csrf_token);
        headers.put("Referer", "https://rentry.co/");
        JSONObject res = new JSONObject(MyUtils.do_http_req_url_encoded("https://rentry.co/api/edit/" + url, "POST", MyUtils.userAgent, headers, args));
        int status = res.getInt("status");
        if (status == 404) {
            throw new RentryDoesntExistException();
        }
        if (status != 200) {
            throw new RuntimeException("rentry edit failed: " + res.optString("errors", "--no content--"));
        }
    }

    public void delete_rentry(String url, String edit_code) throws IOException, RentryDoesntExistException, RentryInvalidEditCodeException {
        create_new_csrf_token_on_demand();

        HashMap<String, String> args = new HashMap<>();
        args.put("csrfmiddlewaretoken", csrf_token);
        args.put("edit_code", edit_code);
        args.put("delete", "delete");

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Cookie", "csrftoken=" + csrf_token);
        headers.put("Referer", "https://rentry.co/");
        MyUtils.HttpResponse resp = MyUtils.do_http_req_url_encoded_raw("https://rentry.co/" + url + "/edit", "POST", MyUtils.userAgent, headers, args);

        if (resp.code == HttpURLConnection.HTTP_MOVED_TEMP) {
            List<String> locations = resp.headers.getOrDefault("Location", new ArrayList<>());
            if (locations != null && !locations.isEmpty()) {
                String location = locations.get(0);
                if (location.equals("/")) {
                    return;
                }
            }
        }

        if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new RentryDoesntExistException();
        }

        String content = resp.content;

        if (content.contains(">Invalid edit code.<")) {
            throw new RentryInvalidEditCodeException();
        }

        throw new RuntimeException("invalid Rentry edit response");
    }

    public void edit_rentry2(String url, String edit_code, String text, String new_edit_code) throws IOException, RentryDoesntExistException, RentryInvalidEditCodeException {
        create_new_csrf_token_on_demand();

        HashMap<String, String> args = new HashMap<>();
        args.put("csrfmiddlewaretoken", csrf_token);
        args.put("edit_code", edit_code);
        args.put("text", text);
        args.put("new_edit_code", new_edit_code);

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Cookie", "csrftoken=" + csrf_token);
        headers.put("Referer", "https://rentry.co/");
        MyUtils.HttpResponse resp = MyUtils.do_http_req_url_encoded_raw("https://rentry.co/" + url + "/edit", "POST", MyUtils.userAgent, headers, args);

        if (resp.code == HttpURLConnection.HTTP_MOVED_TEMP) {
            List<String> locations = resp.headers.getOrDefault("Location", new ArrayList<>());
            if (locations != null && !locations.isEmpty()) {
                String location = locations.get(0);
                if (location.equals("/" + url)) {
                    return;
                }
            }
        }

        if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new RentryDoesntExistException();
        }

        String content = resp.content;

        if (content.contains(">Invalid edit code.<")) {
            throw new RentryInvalidEditCodeException();
        }

        throw new RuntimeException("invalid Rentry edit response");
    }

    public static String read_rentry(String url) throws JSONException, IOException {
        String res = MyUtils.do_http_req_url_encoded("https://rentry.co/api/raw/" + url, "GET", MyUtils.userAgent, new HashMap<>(), new HashMap<>());
        JSONObject obj = new JSONObject(res);
        int status = obj.getInt("status");
        if (status == 404) {
            return null;
        }
        if (status != 200) {
            throw new RuntimeException("rentry create failed: " + obj.optString("errors", "--no content--"));
        }
        return obj.getString("content");
    }

}
