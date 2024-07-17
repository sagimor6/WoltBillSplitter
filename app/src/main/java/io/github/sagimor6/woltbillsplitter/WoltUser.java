package io.github.sagimor6.woltbillsplitter;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class WoltUser {
    public String access_token;
    public String refresh_token;
    public long token_timeout;
    public String my_uid;

    public transient Consumer<WoltUser> on_refresh_cb;

    public WoltUser() {}

    public WoltUser(String wolt_token, long timestamp) throws JSONException {
        set_token(wolt_token, timestamp);
    }

    public void set_token(String wolt_token, long timestamp) throws JSONException {
        JSONObject wolt_tok_json = new JSONObject(wolt_token);
        if (!wolt_tok_json.getString("token_type").equals("Bearer")) {
            throw new JSONException("token type not Bearer");
        }

        String _access_token = wolt_tok_json.getString("access_token");
        String _refresh_token = wolt_tok_json.getString("refresh_token");
        long _token_timeout = timestamp + wolt_tok_json.getLong("expires_in") * 1000 - 5 * 60 * 1000;

        access_token = _access_token;
        refresh_token = _refresh_token;
        token_timeout = _token_timeout;
    }

    public void set_on_refresh_cb(Consumer<WoltUser> cb) {
        on_refresh_cb = cb;
    }

    public void do_refresh_token() throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();
        args.put("grant_type", "refresh_token");
        args.put("refresh_token", refresh_token);

         String wolt_token = MyUtils.do_post("https://authentication.wolt.com/v1/wauth2/access_token", MyUtils.userAgent, args);
         set_token(wolt_token, System.currentTimeMillis());

         if (on_refresh_cb != null) {
             on_refresh_cb.accept(this);
         }
    }

    public void refresh_token_on_demand() throws JSONException, IOException {
        if (System.currentTimeMillis() >= token_timeout) {
            do_refresh_token();
        }
    }

    private String _wolt_api_call(String url, Map<String, String> args) throws JSONException, IOException {
        refresh_token_on_demand();
        return MyUtils.do_bearer_get(url, access_token, args);
    }

    private JSONObject wolt_api_call(String url, Map<String, String> args) throws JSONException, IOException {
        return new JSONObject(_wolt_api_call(url, args));
    }

    public class WoltPostException extends Exception {
        MyUtils.HttpResponse response;
        public WoltPostException(MyUtils.HttpResponse resp) {
            response = resp;
        }
    }

    private JSONObject wolt_api_post(String url, JSONObject json) throws JSONException, IOException, WoltPostException {
        refresh_token_on_demand();

        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + access_token);
        headers.put("Content-Type", "application/json;charset=UTF-8");
        MyUtils.HttpResponse resp = MyUtils.do_http_req(url, "POST", MyUtils.userAgent, headers, (json != null ? json.toString() : "").getBytes(StandardCharsets.UTF_8));
        if (resp.code != HttpURLConnection.HTTP_OK) {
            throw new WoltPostException(resp);
        }
        return new JSONObject(resp.content);
    }

    private void wolt_api_delete(String url) throws JSONException, IOException, WoltPostException {
        refresh_token_on_demand();

        HashMap<String, String> headers = new HashMap<>();

        headers.put("Authorization", "Bearer " + access_token);

        MyUtils.HttpResponse resp = MyUtils.do_http_req(url, "DELETE", MyUtils.userAgent, headers, new byte[0]);
        if (resp.code != HttpURLConnection.HTTP_OK) {
            throw new WoltPostException(resp);
        }
    }

    public JSONObject get_active_orders() throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();
        return wolt_api_call("https://restaurant-api.wolt.com/v2/order_details/subscriptions", args);
    }

    public String do_get_my_uid() throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();
        JSONObject res = wolt_api_call("https://restaurant-api.wolt.com/v1/user/me", args);
        return res.getJSONObject("user").getJSONObject("_id").getString("$oid");
    }

    public JSONArray get_friends(String group_id) throws JSONException, IOException {
        return new JSONArray(_wolt_api_call("https://restaurant-api.wolt.com/v1/group_order/" + group_id + "/friends", null));
    }

    public JSONArray get_order_history(int offset, int num) throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();
        args.put("skip", offset + "");
        args.put("limit", num + "");
        return new JSONArray(_wolt_api_call("https://restaurant-api.wolt.com/v2/order_details/", args));
    }

    public String create_and_delete_dummy_group_order() throws JSONException, IOException {
        JSONObject args = new JSONObject();
        String group_id = null;

        args.put("delivery_method", "takeaway");
        args.put("emoji", "burger");
        args.put("name", "Temp group");
        args.put("venue_id", "645cf359ceef28913966c78d"); // TODO: oh my

        try {
            JSONObject res = wolt_api_post("https://restaurant-api.wolt.com/v1/group_order/", args);
            group_id = res.getString("id");
            wolt_api_delete("https://restaurant-api.wolt.com/v1/group_order/" + group_id);
        } catch (WoltPostException e) {

        }

        return group_id;
    }

    public String get_my_uid() throws JSONException, IOException {
        if (my_uid == null) {
            my_uid = do_get_my_uid();
            if (on_refresh_cb != null) {
                on_refresh_cb.accept(this);
            }
        }
        return my_uid;
    }

    public static void send_login_email(String email) throws JSONException, IOException {
        JSONObject obj = new JSONObject();
        obj.put("attribution", "::");
        obj.put("email", email);
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("H-Captcha-Response", "");
        MyUtils.do_http_req("https://authentication.wolt.com/v2/users/email_login", "POST", MyUtils.userAgent, headers, obj.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String get_email_token_from_email_url(String url) {
        // https://wolt.com/me/magic_login?email=s---%40gmail.com&email_hash=m21CAktUJWrXXTBASFNGV_0x_zFMBWEwAEQeRhcVQB8&token=4_ax_cdOJ8etZOoKrJ97o49miV56JCSSF7Kj-GXeuvA&attribution=%3A%3A

        return Uri.parse(url).getQueryParameter("token");
    }

    public static String get_wolt_token_from_google_token(String google_token) throws IOException {
        HashMap<String, String> args = new HashMap<>();
        args.put("audience", "wolt-com");
        args.put("grant_type", "google_token");
        args.put("google_token", google_token);

        return MyUtils.do_post("https://authentication.wolt.com/v1/wauth2/access_token", MyUtils.userAgent, args);
    }

    public static String get_wolt_token_from_email_token(String token) throws IOException {
        HashMap<String, String> args = new HashMap<>();
        args.put("audience", "restaurant-api");
        args.put("grant_type", "email_login");
        args.put("token", token);

        return MyUtils.do_post("https://authentication.wolt.com/v1/wauth2/access_token", MyUtils.userAgent, args);
    }

    public JSONObject get_orders_details(Set<String> order_ids) throws JSONException, IOException {
        if (order_ids.isEmpty()) {
            throw new RuntimeException("no order ids"); // TODO: fake it
        }
        HashMap<String, String> args = new HashMap<>();
        StringBuilder order_ids_str = new StringBuilder();
        for (String order_id : order_ids) {
            if (order_ids_str.length() > 0) {
                order_ids_str.append(",");
            }
            order_ids_str.append(order_id);
        }
        args.put("group_orders", order_ids_str.toString());
        return wolt_api_call("https://restaurant-api.wolt.com/v2/order_details/by_ids", args);
    }
}
