package io.github.sagimor6.woltbillsplitter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

public class SplitwiseUser {
    public String access_token;
    public long uid = -1;
    public String email = null;

    public transient Consumer<SplitwiseUser> on_save_cb;

    public SplitwiseUser() {}

    public SplitwiseUser(String split_token) throws JSONException {
        JSONObject obj = MyUtils.str_to_json(split_token);
        if (!obj.getString("token_type").equals("bearer")) {
            throw new JSONException("token type not bearer");
        }
        if (obj.has("expires_in")) {
            throw new JSONException("token can expire");
        }
        access_token = obj.getString("access_token");
    }

    public static class SplitwiseApiException extends Exception {
        int resp_code;
        String[] errors;
    }

    public JSONObject do_splitwise_api_post(String url, JSONObject json) throws JSONException, IOException, SplitwiseApiException {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + access_token);
        headers.put("Content-Type", "application/json;charset=UTF-8");
        MyUtils.HttpResponse resp = MyUtils.do_http_req(url, "POST", MyUtils.userAgent, headers, (json != null ? json.toString() : "").getBytes(StandardCharsets.UTF_8));
        if (resp.code != HttpURLConnection.HTTP_OK) {
            SplitwiseApiException e = new SplitwiseApiException();
            e.resp_code = resp.code;
            JSONObject obj = new JSONObject(resp.content);
            if (obj.has("errors")) {
                JSONArray arr = obj.getJSONObject("errors").getJSONArray("base");
                e.errors = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    e.errors[i] = arr.getString(i);
                }
            } else {
                e.errors = new String[1];
                e.errors[0] = obj.getString("error");
            }
            throw e;
        }
        return new JSONObject(resp.content);
    }

    public JSONObject do_splitwise_api_get(String url, Map<String, String> args) throws JSONException, IOException {
        return new JSONObject(MyUtils.do_bearer_get(url, access_token, args));
    }

    public void do_cache_user_details() throws JSONException, IOException {
        JSONObject res = null;
        try {
            res = do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/get_current_user", null);
        } catch (SplitwiseApiException e) {
            throw new RuntimeException(e);
        }
        long _uid = res.getJSONObject("user").getLong("id");
        String _email = res.getJSONObject("user").getString("email");
        uid = _uid;
        email = _email;
    }

    public long get_uid() throws JSONException, IOException {
        if (uid == -1) {
            do_cache_user_details();
            if (on_save_cb != null) {
                on_save_cb.accept(this);
            }
        }
        return uid;
    }

    public String get_email() throws JSONException, IOException {
        if (email == null) {
            do_cache_user_details();
            if (on_save_cb != null) {
                on_save_cb.accept(this);
            }
        }
        return email;
    }

    public void delete_expense(long expense_id) throws JSONException, IOException {
        JSONObject response = null;
        try {
            response = do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/delete_expense/" + expense_id, null);
        } catch (SplitwiseApiException e) {
            throw new RuntimeException(e);
        }
        if (!response.getBoolean("success")) {
            throw new RuntimeException("delete expense failed");
        }
    }

    public JSONArray get_friend_list() throws JSONException, IOException {
        return do_splitwise_api_get("https://secure.splitwise.com/api/v3.0/get_friends", null).getJSONArray("friends");
    }

    public void update_friend_email(long friend_uid, String friend_email) throws JSONException, IOException {
        JSONObject args = new JSONObject();
        args.put("email", friend_email);
        try {
            do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/update_user/" + friend_uid, args);
        } catch (SplitwiseApiException e) {
            // splitwise bug if you change the email to a known email, you get 404 cause the friend doesn't exist anymore
            if (e.resp_code != 404) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean delete_friend(String access_token, long friend_id) throws JSONException, IOException {
        JSONObject res = null;
        try {
            res = do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/delete_friend/" + friend_id, null);
        } catch (SplitwiseApiException e) {
            throw new RuntimeException(e);
        }
        return res.getBoolean("success");
    }

    public JSONArray get_expenses(long group_id, Date from, Date to) throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));

        args.put("group_id", group_id + "");
        args.put("dated_before", df.format(to));
        args.put("dated_after", df.format(from));

        JSONObject response = do_splitwise_api_get("https://secure.splitwise.com/api/v3.0/get_expenses", args);
        return response.getJSONArray("expenses");
    }

    public long create_custom_expense(JSONObject args) throws JSONException, IOException {
        JSONObject response = null;
        try {
            response = do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/create_expense", args);
        } catch (SplitwiseApiException e) {
            throw new RuntimeException(e);
        }
        if (response.getJSONObject("errors").keys().hasNext()) {
            throw new RuntimeException("failed creating expense");
        }
        return response.getJSONArray("expenses").getJSONObject(0).getLong("id");
    }

    public void update_custom_expense(long expense_id, JSONObject args) throws JSONException, IOException {
        JSONObject response = null;
        try {
            response = do_splitwise_api_post("https://secure.splitwise.com/api/v3.0/update_expense/" + expense_id, args);
        } catch (SplitwiseApiException e) {
            throw new RuntimeException(e);
        }
        if (response.getJSONObject("errors").keys().hasNext()) {
            throw new RuntimeException("failed creating expense");
        }
    }
}
