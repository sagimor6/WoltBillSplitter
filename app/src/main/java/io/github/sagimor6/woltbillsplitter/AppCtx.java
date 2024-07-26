package io.github.sagimor6.woltbillsplitter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class AppCtx {

    public WoltUser wolt_user = null;
    public SplitwiseUser split_user = null;
    public RentryUser rentry_user = new RentryUser();
    public Map<String, String> known_users = new HashMap<>(); // wolt_id -> email
    public Set<String> unpaid_orders = new HashSet<>(); // group ids
    public Map<String, PaidWoltOrder> in_processing_orders = new HashMap<>(); // group id -> paid order
    public Map<String, PaidWoltOrder> finished_orders = new HashMap<>();
    public String last_wolt_group_id = null;

    public transient Consumer<AppCtx> on_save_state_cb;

    public static final int MAX_TIME_TO_END_ORDER_MS = 24*60*60*1000;
    public static final int TIME_TO_STOP_PROCESSING_BEFORE_END_MS = 1*60*60*1000;
    public static final int MAX_TIME_TO_STOP_PROCESSING_MS = MAX_TIME_TO_END_ORDER_MS - TIME_TO_STOP_PROCESSING_BEFORE_END_MS;
    public static final int MAX_TIME_TO_FINISH_CYCLE_MS = 3*60*1000;

    public AppCtx() {}

    public static final class PaidWoltOrder {
        public String group_id;
        public String cached_order; // transient?
        public transient JSONObject order;
        boolean finished = false;
        public int rentry_num_users_known = -1;
        public long rentry_last_timestamp = -1;
        public int split_num_users_known = -1;
        public boolean deleted_split_expenses_after_new_rentry_info;

        public PaidWoltOrder() {
        }
    }

    public static final class WoltGroupMember {

        public String first_name;
        public String last_name;
        public String uid;
        public boolean is_me;
        public boolean is_host;
        public int share;
        public int items_price;

        public WoltGroupMember(JSONObject member, boolean _is_me) throws JSONException {
            first_name = member.getString("first_name");
            last_name = member.optString("last_name");
            if (member.has("user_id")) {
                uid = member.getString("user_id");
            } else {
                // apparently people can join without signing in
                // TODO: check this doesn't fuck me
                uid = "guest_" + member.getString("guest_id");
            }
            is_me = _is_me;
            is_host = member.getBoolean("is_host");
            share = member.getInt("total_share");
            items_price = member.getInt("items_price");
        }

        public static List<WoltGroupMember> get_members(JSONObject group) throws JSONException {
            ArrayList<WoltGroupMember> members = new ArrayList<>();
            members.add(new WoltGroupMember(group.getJSONObject("my_member"), true));
            JSONArray other_members = group.getJSONArray("other_members");
            for (int i = 0; i < other_members.length(); i++) {
                members.add(new WoltGroupMember(other_members.getJSONObject(i), false));
            }
            return members;
        }
    }

    public void my_deterministic_shuffle(int[] arr, long seed) {
        Random random = new Random(seed);
        for (int i = 0; i < arr.length - 1; i++) {
            int idx = random.nextInt(arr.length - i) + i;

            // this works also if i == idx
            int temp = arr[idx];
            arr[idx] = arr[i];
            arr[i] = temp;
        }
    }

    public long create_wolt_expense(JSONObject wolt_order, long expense_id) throws JSONException, IOException {
        JSONObject args = new JSONObject();

        JSONObject group = wolt_order.getJSONObject("group");
        int cost = wolt_order.getInt("subtotal");

        String venue_name = wolt_order.getString("venue_name");

        List<WoltGroupMember> members = WoltGroupMember.get_members(group);

        long date_long = wolt_order.getJSONObject("payment_time").getLong("$date");
        Date date = new Date(date_long);

        int shared_cost_part = cost - wolt_order.getInt("items_price");
        int num_paying_members = 0;

        for (WoltGroupMember m : members) {
            if (m.items_price != 0) { // troll members are excused (including myself)
                num_paying_members++;
            }
        }

        if (num_paying_members == 0) { // y'all trolls and you gonna pay
            num_paying_members = members.size();
        }

        int per_member_shared_cost = shared_cost_part / num_paying_members;
        int residual_cost = shared_cost_part % num_paying_members;

        for (WoltGroupMember m : members) {
            if (m.items_price != 0 || num_paying_members == members.size()) {
                m.share = m.items_price + per_member_shared_cost;
            } else {
                m.share = 0;
            }
        }

        if (residual_cost != 0) {
            ArrayList<WoltGroupMember> paying_members_no_host_list = new ArrayList<>();
            for (WoltGroupMember m : members) {
                if (!m.is_host && (m.items_price != 0 || num_paying_members == members.size())) {
                    paying_members_no_host_list.add(m);
                }
            }
            // size is num_paying_members or num_paying_members - 1
            // and resid <= num_paying_members - 1
            WoltGroupMember[] paying_members_no_host_arr = paying_members_no_host_list.toArray(new WoltGroupMember[0]);

            Arrays.sort(paying_members_no_host_arr, Comparator.comparing(m -> m.uid));
            int[] idxs = new int[paying_members_no_host_arr.length];
            for (int i = 0; i < idxs.length; i++) {
                idxs[i] = i;
            }
            my_deterministic_shuffle(idxs, date_long);

            for (int i = 0; i < residual_cost; i++) {
                paying_members_no_host_arr[idxs[i]].share += 1;
            }
        }

        args.put("cost", String.format(Locale.ENGLISH, "%d.%02d", cost / 100, cost % 100));
        args.put("description", "Wolt order from: " + venue_name);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        args.put("date", df.format(date));
        args.put("repeat_interval", "never");
        args.put("currency_code", wolt_order.getString("currency"));
        args.put("category_id", 0);
        args.put("group_id", 0);

        int i = 0;
        int num_valid = 0;
        for (WoltGroupMember m : members) {
            if (m.is_host) {
                args.put("users__" + i + "__paid_share", String.format(Locale.ENGLISH, "%d.%02d", cost / 100, cost % 100));
            } else {
                args.put("users__" + i + "__paid_share", "0");
            }
            args.put("users__" + i + "__owed_share", String.format(Locale.ENGLISH, "%d.%02d", m.share / 100, m.share % 100));

            if (!m.is_me) {
                String email = known_users.get(m.uid);
                if (email == null) {
                    email = m.uid + "@wolt.invalid";
                } else {
                    num_valid++;
                }
                args.put("users__" + i + "__first_name", m.first_name);
                args.put("users__" + i + "__last_name", m.last_name);
                args.put("users__" + i + "__email", email);
            } else {
                args.put("users__" + i + "__user_id", split_user.get_uid());
                num_valid++;
            }
            i++;
        }

        args.put("details", "wolt link " + group.getString("url") + " , num_valid=" + num_valid);

        if (expense_id < 0) {
            return split_user.create_custom_expense(args);
        } else {
            split_user.update_custom_expense(expense_id, args);
            return expense_id;
        }
    }

    private static final byte[] HMAC_KEY_URL_GEN = new byte[] {(byte)0x6C, (byte)0x56, (byte)0x83, (byte)0x74, (byte)0x70, (byte)0xAF, (byte)0x01, (byte)0x43, (byte)0x24, (byte)0x78, (byte)0x63, (byte)0x8B, (byte)0xCD, (byte)0x17, (byte)0xF1, (byte)0x21, (byte)0x59, (byte)0x4A, (byte)0xFF, (byte)0x8B, (byte)0x48, (byte)0x0E, (byte)0x84, (byte)0x93, (byte)0xA5, (byte)0xC5, (byte)0xED, (byte)0xEB, (byte)0x2A, (byte)0x3E, (byte)0xBA, (byte)0xC3};
    private static final byte[] HMAC_KEY_MSG_VERIFY_HMAC = new byte[] {(byte)0x2D, (byte)0xD9, (byte)0x99, (byte)0xC0, (byte)0x13, (byte)0x78, (byte)0x28, (byte)0x7A, (byte)0xAB, (byte)0x9B, (byte)0x57, (byte)0x72, (byte)0x89, (byte)0x4B, (byte)0x26, (byte)0xDC, (byte)0xCF, (byte)0x68, (byte)0xB5, (byte)0x77, (byte)0x66, (byte)0x7B, (byte)0x6C, (byte)0x38, (byte)0x9A, (byte)0x82, (byte)0x71, (byte)0xB5, (byte)0xD9, (byte)0xA0, (byte)0x04, (byte)0x18};
    private static final byte[] HMAC_KEY_MSG_ENC = new byte[] {(byte)0xB9, (byte)0xE4, (byte)0x2C, (byte)0x48, (byte)0x42, (byte)0x35, (byte)0xB3, (byte)0x9F, (byte)0x68, (byte)0x20, (byte)0x21, (byte)0xD8, (byte)0x25, (byte)0xD2, (byte)0x18, (byte)0x51, (byte)0x92, (byte)0xA7, (byte)0x06, (byte)0x9D, (byte)0x1E, (byte)0xFB, (byte)0xC6, (byte)0xB4, (byte)0x89, (byte)0x2D, (byte)0xB8, (byte)0x4E, (byte)0xD6, (byte)0xE9, (byte)0x32, (byte)0xB4};
    private static final byte[] HMAC_KEY_MSG_ENC_IV = new byte[] {(byte)0xA6, (byte)0x52, (byte)0xAB, (byte)0xF7, (byte)0x01, (byte)0x4E, (byte)0x14, (byte)0x2E, (byte)0x1F, (byte)0x58, (byte)0x40, (byte)0xC9, (byte)0xDE, (byte)0xBA, (byte)0xC8, (byte)0x60, (byte)0x20, (byte)0x4E, (byte)0x08, (byte)0x97, (byte)0x5E, (byte)0xE6, (byte)0x76, (byte)0x72, (byte)0x67, (byte)0xD1, (byte)0x17, (byte)0xB1, (byte)0xAF, (byte)0x74, (byte)0xA5, (byte)0xB1};
    private static final byte[] HMAC_KEY_EDIT_CODE = new byte[] {(byte)0x83, (byte)0x63, (byte)0x87, (byte)0x38, (byte)0x0D, (byte)0x9C, (byte)0xE1, (byte)0xC2, (byte)0x61, (byte)0xC4, (byte)0x53, (byte)0xDB, (byte)0xCB, (byte)0x56, (byte)0x03, (byte)0x77, (byte)0x58, (byte)0x48, (byte)0x5E, (byte)0x4C, (byte)0x45, (byte)0xB5, (byte)0x04, (byte)0xE4, (byte)0x92, (byte)0xF7, (byte)0x10, (byte)0x0F, (byte)0xF1, (byte)0x2F, (byte)0x09, (byte)0xDC};

    public void post_info_on_rentry(String group_id, Map<String, String> friends) throws JSONException, IOException {

        MyLogger.e("publish rentry with " + friends.size());

        // TODO: maybe hash them before
        byte[] master_key = (group_id + wolt_user.get_my_uid()).getBytes(StandardCharsets.UTF_8);

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        byte[] text_hmac_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_VERIFY_HMAC);
        byte[] text_aes_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC);
        byte[] iv = Arrays.copyOf(MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC_IV), MyUtils.AES_BLOCK_SIZE);

        JSONObject obj = new JSONObject();
        JSONObject friend_arr = new JSONObject();
        String text;

        for (Map.Entry<String, String> entry : friends.entrySet()) {
            friend_arr.put(entry.getKey(), entry.getValue());
        }
        obj.put("version", 1);
        obj.put("users", friend_arr);
        text = obj.toString();

        byte[] text_bytes = text.getBytes(StandardCharsets.UTF_8);

        byte[] enc_text = MyUtils.do_aes_cbc(text_bytes, text_aes_key, iv, true);

        byte[] whole_hmac = MyUtils.do_hmac(enc_text, text_hmac_key);

        byte[] whole = MyUtils.concat_bytes(whole_hmac, enc_text);

        String whole_text = Base64.encodeToString(whole, Base64.NO_WRAP);

        try {
            rentry_user.edit_rentry(url, "edit_code", whole_text);
        } catch (RentryDoesntExistException e) {
            // TODO: maybe cache the state and not try to edit first
            try {
                rentry_user.create_rentry(url, "edit_code", whole_text);
            } catch (RentryAlreadyExistsException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public boolean post_info_on_rentry2(String group_id, String host_id, int cur_num, Map<String, String> friends) throws JSONException, IOException {

        MyLogger.e("publish rentry with " + friends.size());

        // TODO: maybe hash them before
        byte[] master_key = (group_id + host_id).getBytes(StandardCharsets.UTF_8);
        master_key = MyUtils.do_sha256(master_key); // standardize

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        byte[] text_hmac_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_VERIFY_HMAC);
        byte[] text_aes_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC);
        byte[] iv = MyUtils.do_random(MyUtils.AES_BLOCK_SIZE);

        byte[] new_edit_code_bytes = MyUtils.concat_bytes(master_key, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(friends.size()).array());
        new_edit_code_bytes = MyUtils.do_hmac(new_edit_code_bytes, HMAC_KEY_EDIT_CODE);
        String new_edit_code = Base64.encodeToString(new_edit_code_bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);

        JSONObject obj = new JSONObject();
        JSONObject friend_arr = new JSONObject();
        String text;

        for (Map.Entry<String, String> entry : friends.entrySet()) {
            friend_arr.put(entry.getKey(), entry.getValue());
        }
        obj.put("version", 1);
        obj.put("users", friend_arr);
        text = obj.toString();

        byte[] text_bytes = text.getBytes(StandardCharsets.UTF_8);

        byte[] enc_text = MyUtils.do_aes_cbc(text_bytes, text_aes_key, iv, true);

        enc_text = MyUtils.concat_bytes(iv, enc_text);

        byte[] whole_hmac = MyUtils.do_hmac(enc_text, text_hmac_key);

        byte[] whole = MyUtils.concat_bytes(whole_hmac, enc_text);

        String whole_text = Base64.encodeToString(whole, Base64.NO_WRAP);

        if (cur_num == 0) {
            try {
                rentry_user.create_rentry(url, new_edit_code, whole_text);
            } catch (RentryAlreadyExistsException e) {
                return false; // someone updated before us
            }
        } else {
            byte[] edit_code_bytes = MyUtils.concat_bytes(master_key, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(cur_num).array());
            edit_code_bytes = MyUtils.do_hmac(edit_code_bytes, HMAC_KEY_EDIT_CODE);
            String edit_code = Base64.encodeToString(edit_code_bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);

            try {
                rentry_user.edit_rentry2(url, edit_code, whole_text, new_edit_code);
            } catch (RentryDoesntExistException e) {
                throw new RuntimeException(e);
            } catch (RentryInvalidEditCodeException e) {
                return false; // someone updated before us
            }
        }
        return true;
    }

    public void del_info_on_rentry(String group_id) throws JSONException, IOException {
        byte[] master_key = (group_id + wolt_user.get_my_uid()).getBytes(StandardCharsets.UTF_8);

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        try {
            rentry_user.delete_rentry(url, "edit_code");
        } catch (RentryDoesntExistException | RentryInvalidEditCodeException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean del_info_on_rentry2(String group_id, String host_id, int cur_num) throws JSONException, IOException {
        byte[] master_key = (group_id + host_id).getBytes(StandardCharsets.UTF_8);
        master_key = MyUtils.do_sha256(master_key); // standardize

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        byte[] edit_code_bytes = MyUtils.concat_bytes(master_key, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(cur_num).array());
        edit_code_bytes = MyUtils.do_hmac(edit_code_bytes, HMAC_KEY_EDIT_CODE);
        String edit_code = Base64.encodeToString(edit_code_bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);

        try {
            rentry_user.delete_rentry(url, edit_code);
        } catch (RentryDoesntExistException e) {
            return true;
        } catch (RentryInvalidEditCodeException e) {
            return false;
        }
        return true;
    }

    public Map<String, String> get_info_on_rentry(String group_id, String uid) throws JSONException, IOException {
        HashMap<String, String> user_list = new HashMap<>();

        // TODO: maybe hash them before
        byte[] master_key = (group_id + uid).getBytes(StandardCharsets.UTF_8);

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        byte[] text_hmac_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_VERIFY_HMAC);
        byte[] text_aes_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC);
        byte[] iv = Arrays.copyOf(MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC_IV), MyUtils.AES_BLOCK_SIZE);

        String msg = RentryUser.read_rentry(url);
        if (msg == null) {
            return user_list;
        }
        byte[] msg_bytes = Base64.decode(msg, Base64.NO_WRAP);

        byte[] msg_hmac = Arrays.copyOfRange(msg_bytes, 0, MyUtils.SHA256_DIGEST_SIZE);
        msg_bytes = Arrays.copyOfRange(msg_bytes, MyUtils.SHA256_DIGEST_SIZE, msg_bytes.length);

        if (!Arrays.equals(MyUtils.do_hmac(msg_bytes, text_hmac_key), msg_hmac)) {
            throw new RuntimeException("hmac doesnt match");
        }

        msg_bytes = MyUtils.do_aes_cbc(msg_bytes, text_aes_key, iv, false);

        msg = new String(msg_bytes, StandardCharsets.UTF_8);

        JSONObject obj = new JSONObject(msg);
        if (obj.getInt("version") != 1) {
            throw new RuntimeException("version not 1");
        }
        JSONObject users_obj = obj.getJSONObject("users");
        for (Iterator<String> iter = users_obj.keys(); iter.hasNext(); ) {
            String wolt_id = iter.next();
            user_list.put(wolt_id, users_obj.getString(wolt_id));
        }

        if (!user_list.containsKey(uid)) {
            throw new RuntimeException("user list doesn't contain the owner?");
        }

        return user_list;
    }

    public Map<String, String> get_info_on_rentry2(String group_id, String host_id) throws JSONException, IOException {
        HashMap<String, String> user_list = new HashMap<>();

        // TODO: maybe hash them before
        byte[] master_key = (group_id + host_id).getBytes(StandardCharsets.UTF_8);
        master_key = MyUtils.do_sha256(master_key); // standardize

        byte[] url_bytes = MyUtils.do_hmac(master_key, HMAC_KEY_URL_GEN);
        String url = MyUtils.bytesToHex(url_bytes);

        byte[] text_hmac_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_VERIFY_HMAC);
        byte[] text_aes_key = MyUtils.do_hmac(master_key, HMAC_KEY_MSG_ENC);

        String msg = RentryUser.read_rentry(url);
        if (msg == null) {
            return user_list;
        }
        byte[] msg_bytes = Base64.decode(msg, Base64.NO_WRAP);

        if (msg_bytes.length < MyUtils.SHA256_DIGEST_SIZE) {
            throw new RuntimeException("msg without hmac?");
        }

        byte[] msg_hmac = Arrays.copyOfRange(msg_bytes, 0, MyUtils.SHA256_DIGEST_SIZE);
        msg_bytes = Arrays.copyOfRange(msg_bytes, MyUtils.SHA256_DIGEST_SIZE, msg_bytes.length);

        if (!Arrays.equals(MyUtils.do_hmac(msg_bytes, text_hmac_key), msg_hmac)) {
            throw new RuntimeException("hmac doesnt match");
        }

        if (msg_bytes.length < MyUtils.AES_BLOCK_SIZE) {
            throw new RuntimeException("msg without iv?");
        }

        byte[] iv = Arrays.copyOfRange(msg_bytes, 0, MyUtils.AES_BLOCK_SIZE);
        msg_bytes = Arrays.copyOfRange(msg_bytes, MyUtils.AES_BLOCK_SIZE, msg_bytes.length);

        msg_bytes = MyUtils.do_aes_cbc(msg_bytes, text_aes_key, iv, false);

        msg = new String(msg_bytes, StandardCharsets.UTF_8);

        JSONObject obj = new JSONObject(msg);
        if (obj.getInt("version") != 1) {
            throw new RuntimeException("version not 1");
        }
        JSONObject users_obj = obj.getJSONObject("users");
        for (Iterator<String> iter = users_obj.keys(); iter.hasNext(); ) {
            String wolt_id = iter.next();
            user_list.put(wolt_id, users_obj.getString(wolt_id));
        }

        if (user_list.isEmpty()) {
            throw new RuntimeException("user list doesn't contain anyone?");
        }

        return user_list;
    }

    public Map<Long, Integer> get_wolt_expenses_of_order(JSONObject wolt_order) throws JSONException, IOException {
        HashMap<String, String> args = new HashMap<>();

        HashMap<Long, Integer> expenses_ids = new HashMap<>();

        JSONObject group = wolt_order.getJSONObject("group");

        long date_long = wolt_order.getJSONObject("payment_time").getLong("$date");

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));

        Date date_minus_sec = new Date(date_long - 1000);
        Date date_plus_sec = new Date(date_long + 1000);
        Date date = new Date(date_long);
        String date_str = df.format(date);

        Pattern p = Pattern.compile("^wolt link " + Pattern.quote(group.getString("url")) + " , num_valid=(\\d+)$");

        args.put("group_id", "0");
        args.put("dated_before", df.format(date_plus_sec));
        args.put("dated_after", df.format(date_minus_sec));


        JSONArray expenses = split_user.get_expenses(0, date_minus_sec, date_plus_sec);
        for (int i = 0; i < expenses.length(); i++) {
            JSONObject expense = expenses.getJSONObject(i);
            if (!expense.getString("date").equals(date_str)) {
                continue;
            }
            if (!expense.isNull("deleted_at")) {
                continue;
            }

            Matcher matcher = p.matcher(expense.getString("details"));
            if (!matcher.find()) {
                continue;
            }
            String num_str = matcher.group(1);
            if (num_str == null) {
                continue;
            }
            int num_valid;
            try {
                num_valid = Integer.parseInt(num_str, 10);
            } catch (NumberFormatException e) {
                continue;
            }

            expenses_ids.put(expense.getLong("id"), num_valid);
        }

        return expenses_ids;
    }

    public void process_wolt_order(PaidWoltOrder order) throws JSONException, IOException {
        JSONObject wolt_order = order.order;
        if (wolt_order == null) {
            order.order = new JSONObject(order.cached_order);
            wolt_order = order.order;
        }

        JSONObject group = wolt_order.getJSONObject("group");
        String group_id = group.getString("id");
        long payment_time = wolt_order.getJSONObject("payment_time").getLong("$date");

        List<WoltGroupMember> members = WoltGroupMember.get_members(group);

        HashMap<String, String> users = new HashMap<>();
        HashSet<String> unknown_friends = new HashSet<>();
        String host_id = null;

        members.forEach(member -> {
            String email = known_users.get(member.uid);
            if (email == null) {
                unknown_friends.add(member.uid);
            } else {
                users.put(member.uid, email);
            }
        });

        if (users.isEmpty()) {
            throw new RuntimeException("users empty");
        }

        for (WoltGroupMember member : members) {
            if (member.is_host) {
                host_id = member.uid;
            }
        }

        if (host_id == null) {
            throw new RuntimeException("order without host?");
        }

        int last_rentry_num_users_when_posting = -1;

        boolean stop_loop = false;

        int iterations = 0;

        long timestamp = System.currentTimeMillis();;

        while (!stop_loop) {
            if (iterations > members.size()) {
                throw new RuntimeException("too much iters");
            }
            iterations++;

            timestamp = System.currentTimeMillis();

            // check friends
            {
                int last_rentry_num_users = order.rentry_num_users_known;
                boolean updated = false;

                if (!unknown_friends.isEmpty() || last_rentry_num_users < users.size()) {
                    Map<String, String> users_from_net = get_info_on_rentry2(group_id, host_id);

                    timestamp = System.currentTimeMillis();

                    if (last_rentry_num_users_when_posting == users_from_net.size()) {
                        throw new RuntimeException("edit code wtf?");
                    }

                    last_rentry_num_users_when_posting = users_from_net.size();

                    last_rentry_num_users = users_from_net.size();

                    if (last_rentry_num_users < order.rentry_num_users_known) {
                        if (timestamp < payment_time + MAX_TIME_TO_STOP_PROCESSING_MS || last_rentry_num_users != 0) {
                            throw new RuntimeException("someone is trolling us?");
                        }
                    }

                    for (Map.Entry<String, String> entry : users_from_net.entrySet()) {
                        if (unknown_friends.contains(entry.getKey())) {
                            unknown_friends.remove(entry.getKey());
                            users.put(entry.getKey(), entry.getValue());
                            known_users.put(entry.getKey(), entry.getValue());
                            updated = true;
                        } else {
                            String email = users.get(entry.getKey());
                            if (email == null) {
                                throw new RuntimeException("someone is trolling us? user out of scope");
                            }
                            if (!email.equals(entry.getValue())) {
                                throw new RuntimeException("someone is trolling us? email mismatch");
                            }
                        }
                    }
                }

                // TODO: uncomment this?
//                if (updated) {
//                    save_state();
//                    updated = false;
//                }

                if (timestamp < payment_time + MAX_TIME_TO_STOP_PROCESSING_MS) {
                    if (last_rentry_num_users < users.size()) {
                        if (!post_info_on_rentry2(group_id, host_id, last_rentry_num_users, users)) {
                            continue; // try again, someone updated before us
                        }
                        last_rentry_num_users = users.size();
                        timestamp = System.currentTimeMillis();
                    }
                }

                if (timestamp >= payment_time + MAX_TIME_TO_END_ORDER_MS) {
                    if (last_rentry_num_users != 0) {
                        if (!del_info_on_rentry2(group_id, host_id, last_rentry_num_users)) {
                            continue;
                        }
                    }
                }

                if (order.rentry_num_users_known < last_rentry_num_users) {
                    order.rentry_num_users_known = last_rentry_num_users;
                    order.rentry_last_timestamp = timestamp;
                    order.deleted_split_expenses_after_new_rentry_info = false;
                    updated = true;
                }

                if (updated) {
                    save_state();
                }

                last_rentry_num_users_when_posting = -1;
            }

            if (order.split_num_users_known >= users.size()) {
                stop_loop = true;
            }

//            // check friends
//            {
//                HashSet<String> unknown_friends = new HashSet<>();
//                members.forEach(member -> {
//                    if (!known_users.containsKey(member.uid)) {
//                        unknown_friends.add(member.uid);
//                    }
//                });
//
//                boolean updated = false;
//
//                for (WoltGroupMember member : members) {
//                    if (unknown_friends.isEmpty()) {
//                        break;
//                    }
//                    if (member.is_me) {
//                        continue;
//                    }
//                    Map<String, String> users = get_info_on_rentry(group_id, member.uid);
//                    for (Map.Entry<String, String> entry : users.entrySet()) {
//                        if (unknown_friends.contains(entry.getKey())) {
//                            unknown_friends.remove(entry.getKey());
//                            known_users.put(entry.getKey(), entry.getValue());
//                            updated = true;
//                        } else {
//                            if (!known_users.get(member.uid).equals(entry.getValue())) {
//                                throw new RuntimeException("troll?");
//                            }
//                        }
//                    }
//                }
//
//                if (updated) {
//                    save_state();
//                }
//            }
//
//            HashMap<String, String> users = new HashMap<>();
//            {
//                for (WoltGroupMember member : members) {
//                    String email = known_users.get(member.uid);
//                    if (email != null) {
//                        users.put(member.uid, email);
//                    }
//                }
//
//                if (order.split_num_users_known >= users.size()) {
//                    stop_loop = true;
//                }
//
//                if (order.rentry_num_users_known < users.size()) {
//                    post_info_on_rentry(group_id, users);
//
//                    order.rentry_num_users_known = users.size();
//                    save_state();
//                }
//            }

            if (!order.finished) {
                break;
            }


            if (!(
                    (users.size() > order.split_num_users_known && timestamp < payment_time + MAX_TIME_TO_STOP_PROCESSING_MS)
                    || (timestamp >= order.rentry_last_timestamp + MAX_TIME_TO_FINISH_CYCLE_MS && !order.deleted_split_expenses_after_new_rentry_info)
            )) {
                break;
            }

            Map<Long, Integer> expense_ids = get_wolt_expenses_of_order(wolt_order);
            long best_expense_id = -1;
            int best_num_valid = -1;
            for (Map.Entry<Long, Integer> entry : expense_ids.entrySet()) {
                if (entry.getValue() > best_num_valid || (entry.getValue() == best_num_valid && entry.getKey() < best_expense_id)) {
                    best_num_valid = entry.getValue();
                    best_expense_id = entry.getKey();
                }
            }

            if (best_num_valid < users.size() && timestamp < payment_time + MAX_TIME_TO_STOP_PROCESSING_MS) {
                MyLogger.e("creating splitwise with " + users.size());
                best_expense_id = create_wolt_expense(wolt_order, -1);
                best_num_valid = users.size();
            }

            if (order.split_num_users_known != best_num_valid) {
                order.split_num_users_known = best_num_valid;
                save_state();
            }

            for (long expense_id : expense_ids.keySet()) {
                if (expense_id == best_expense_id) {
                    continue;
                }
                split_user.delete_expense(expense_id);
            }

            if (timestamp >= order.rentry_last_timestamp + MAX_TIME_TO_FINISH_CYCLE_MS && !order.deleted_split_expenses_after_new_rentry_info) {
                order.deleted_split_expenses_after_new_rentry_info = true;
                save_state();
            }
        }


        if (timestamp >= payment_time + MAX_TIME_TO_END_ORDER_MS) {
            finished_orders.remove(order.group_id); // TODO: this is crap, we should have one list
            in_processing_orders.remove(order.group_id);
            save_state();
        }

//        if (order.finished && order.split_num_users_known >= members.size()) {
//            finished_orders.remove(order.group_id);
//            save_state();
//        }
    }

    public void save_state() {
        on_save_state_cb.accept(this);
    }

    public Set<String> update_orders_from_wolt_response(JSONObject orders) throws JSONException {
        JSONArray unpaid_group_orders = orders.getJSONArray("group_orders");
        HashSet<String> updated_orders = new HashSet<>();

        for (int i = 0; i < unpaid_group_orders.length(); i++) {
            JSONObject order = unpaid_group_orders.getJSONObject(i);
            String status = order.getString("status");
            String group_id = order.getString("id");
            last_wolt_group_id = group_id;
            if (status.equals("cancelled")) {
                unpaid_orders.remove(group_id);
            } else {
                unpaid_orders.add(group_id);
            }
            updated_orders.add(group_id);
        }

        JSONArray paid_group_orders = orders.getJSONArray("order_details");
        for (int i = 0; i < paid_group_orders.length(); i++) {
            JSONObject order = paid_group_orders.getJSONObject(i);
            String status = order.getString("status");
            if (!order.has("group")) {
                continue;
            }

            String group_id = order.getJSONObject("group").getString("id");

            last_wolt_group_id = group_id;

            unpaid_orders.remove(group_id);

            switch (status) {
                case "rejected":
                case "deferred_payment_failed":
                case "process_payment_failed":
                case "payment_method_not_valid_error":
                case "invalid":
                    in_processing_orders.remove(group_id);
                    break;
                case "refunded": // TODO: what is this
                case "delivered": {
                    PaidWoltOrder paidWoltOrder = in_processing_orders.get(group_id);
                    if(paidWoltOrder == null) {
                        paidWoltOrder = new PaidWoltOrder();
                    } else {
                        in_processing_orders.remove(group_id);
                    }
                    paidWoltOrder.cached_order = order.toString();
                    paidWoltOrder.order = order;
                    paidWoltOrder.group_id = group_id;
                    paidWoltOrder.finished = true;
                    finished_orders.put(group_id, paidWoltOrder);
                    break;
                }

//                    case "pending_revenue_transaction": // TODO: maybe change name from PaidWoltOrder to LockedWoltOrder
//                    case "received":
//                    case "fetched":
//                    case "acknowledged":
//                    case "production":
//                    case "estimated":
//                    case "ready":
//                    case "picked_up":
                default: {
                    PaidWoltOrder paidWoltOrder = in_processing_orders.get(group_id);
                    if(paidWoltOrder == null) {
                        paidWoltOrder = new PaidWoltOrder();
                    }
                    paidWoltOrder.cached_order = order.toString();
                    paidWoltOrder.order = order;
                    paidWoltOrder.group_id = group_id;
                    in_processing_orders.put(group_id, paidWoltOrder);
                    break;
                }
            }
            updated_orders.add(group_id);
        }

        return updated_orders;
    }

    public void check_wolt_orders_status() throws JSONException, IOException {
        JSONObject orders = wolt_user.get_active_orders();
        Set<String> updated_orders;

        updated_orders = update_orders_from_wolt_response(orders);

        if (!updated_orders.isEmpty()) {
            save_state();
        }

        HashSet<String> to_update_group_ids = new HashSet<>(unpaid_orders);
        to_update_group_ids.addAll(in_processing_orders.keySet());
        to_update_group_ids.removeAll(updated_orders);

        if (!to_update_group_ids.isEmpty()) {
            orders = wolt_user.get_orders_details(to_update_group_ids);

            update_orders_from_wolt_response(orders);

            save_state();
        }
    }

    public void process_all_wolt_orders() throws JSONException, IOException {
        ArrayList<PaidWoltOrder> orders = new ArrayList<>(in_processing_orders.values());
        orders.addAll(finished_orders.values());

        for (PaidWoltOrder order : orders) {
            MyLogger.e("processing group order " + order.group_id);
            process_wolt_order(order);
        }
    }

    public void fix_all_splitwise_friends() throws JSONException, IOException {
        JSONArray friends = split_user.get_friend_list();
        for (int i = 0; i < friends.length(); i++) {
            JSONObject friend = friends.getJSONObject(i);
            String email = friend.getString("email");
            if (!email.endsWith("@wolt.invalid")) {
                continue;
            }
            String wolt_id = email.substring(0, email.length() - "@wolt.invalid".length());
            String true_email = known_users.get(wolt_id);
            if (true_email == null) {
                continue;
            }

            long split_id = friend.getLong("id");

            split_user.update_friend_email(split_id, true_email);
        }
    }

    public void do_single_cycle() throws JSONException, IOException {
        if (!split_user.get_email().equals(known_users.get(wolt_user.get_my_uid()))) {
            known_users.put(wolt_user.get_my_uid(), split_user.get_email());
            save_state();
        }
//        try {
            check_wolt_orders_status();
//        } catch (IOException e) {
//            // nonfatal, we can still try processing to existing orders
//        }

        process_all_wolt_orders();

        fix_all_splitwise_friends();
    }

    public void fill_transient_state() {
        if (wolt_user != null) {
            wolt_user.on_refresh_cb = woltUser -> {
                save_state();
            };
        }

        if(split_user != null) {
            split_user.on_save_cb = splitwiseUser -> {
                save_state();
            };
        }

        rentry_user.on_save_cb = rentryUser -> {
            save_state();
        };

        // check if we have been updated
        if (update_info != null && MyUtils.compare_versions(update_info.ver, MyUtils.my_package_version) <= 0) {
            update_info = null;
            save_state();
        }
    }

    public void get_last_wolt_group_order() throws JSONException, IOException {
        if (last_wolt_group_id != null) {
            return;
        }

        final int BLOCK_SIZE = 50;

        int offset = 0;
        while(true) {
            JSONArray last_orders = wolt_user.get_order_history(offset, BLOCK_SIZE);
            for (int i = 0; i < last_orders.length(); i++) {
                JSONObject order = last_orders.getJSONObject(i);
                if (!order.has("group")) {
                    continue;
                }
                last_wolt_group_id = order.getJSONObject("group").getString("id");
                save_state();
                return;
            }
            if (last_orders.length() != BLOCK_SIZE) {
                break;
            }
            offset += BLOCK_SIZE;
        }

        last_wolt_group_id = wolt_user.create_and_delete_dummy_group_order();
    }

    public JSONArray get_wolt_friends() throws JSONException, IOException {
        get_last_wolt_group_order();
        if (last_wolt_group_id == null) {
            return new JSONArray();
        }

        return wolt_user.get_friends(last_wolt_group_id);
    }

    public void set_wolt_user(WoltUser new_wolt_user) {
        if (wolt_user != null && wolt_user.my_uid != null) {
            known_users.remove(wolt_user.my_uid);
        }
        in_processing_orders.clear();
        finished_orders.clear();
        unpaid_orders.clear();
        wolt_user = new_wolt_user;
        last_wolt_group_id = null;

        fill_transient_state();
        save_state();
    }

    public void set_splitwise_user(SplitwiseUser new_splitwise_user) {
        if (wolt_user != null && wolt_user.my_uid != null) {
            known_users.remove(wolt_user.my_uid);
        }
        in_processing_orders.clear();
        finished_orders.clear();
        unpaid_orders.clear();
        split_user = new_splitwise_user;

        fill_transient_state();
        save_state();
    }

    public UpdateInfo update_info;
    public long last_time_new_update_wasnt_found = -1;
    public long update_num_bytes_written = 0;
    public long update_size = -1;
    public boolean was_just_before_session_create = false;
    public int update_session_id = -1;
    public String update_download_validator = null;
    public boolean update_is_validated = false;

    public transient boolean update_finished = false;

    // TODO: i admit, all the logic concerning this is a bit flawed
    public boolean did_user_give_consent_downloading_on_metered = false;

    public void get_current_install_session_id(Context ctx) {
        PackageInstaller packageInstaller = ctx.getPackageManager().getPackageInstaller();

        boolean session_valid = false;

        List<PackageInstaller.SessionInfo> sessionInfos = packageInstaller.getMySessions();

        if (was_just_before_session_create) {
            if(sessionInfos.size() == 1) {
                update_session_id = sessionInfos.get(0).getSessionId();
            } else {
                update_session_id = -1;
            }
            was_just_before_session_create = false;
            save_state();
        }

        for (PackageInstaller.SessionInfo sessionInfo : sessionInfos) {
            if (sessionInfo.getSessionId() == update_session_id) {
                session_valid = true;
            } else {
                // TODO: ?
                packageInstaller.abandonSession(sessionInfo.getSessionId());
            }
        }

        if (!session_valid && update_session_id >= 0) {
            update_session_id = -1;
            save_state();
        }
    }

    public void create_install_session_id(Context ctx) throws IOException {
        PackageInstaller packageInstaller = ctx.getPackageManager().getPackageInstaller();

        if (update_session_id < 0) {
            PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionParams.setAppPackageName(MyUtils.my_package_name);
            was_just_before_session_create = true;
            save_state();
            update_session_id = packageInstaller.createSession(sessionParams);
            was_just_before_session_create = false;
            save_state();
        }
    }

    public static final Pattern updater_info_pattern = Pattern.compile("(^|\\s)\\s*INFO FOR UPDATER:::([a-zA-Z0-9/=]+)\\s*(\\s|$)");

    public static final Pattern version_pattern = Pattern.compile("^(0|([1-9][0-9]*))(\\.(0|([1-9][0-9]*)))*$");

    public static boolean check_github_url(String url_str) {
        URL url;
        try {
            url = new URL(url_str);
        } catch (MalformedURLException e) {
            return false;
        }

        if (!url.getProtocol().equals("https")) {
            return false;
        }

        String host = url.getHost();
        final String github_domain = "github.com";
        if (!(host.equals(github_domain) || host.endsWith("." + github_domain))) {
            return false;
        }

        return true;
    }

    public static boolean is_version_string_valid(String version) {
        return version_pattern.matcher(version).matches();
    }

    public static final class UpdateInfo {
        public String ver;
        public String base64_info;
        public String apk_url;
        public byte[] apk_signature;
        public transient String apk_sig_url;

        public UpdateInfo() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UpdateInfo that = (UpdateInfo) o;
            return Objects.equals(ver, that.ver) && Objects.equals(base64_info, that.base64_info) && Objects.equals(apk_url, that.apk_url) && Arrays.equals(apk_signature, that.apk_signature);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(ver, base64_info, apk_url);
            result = 31 * result + Arrays.hashCode(apk_signature);
            return result;
        }
    }

    public boolean update_process_single_release_record(UpdateInfo latest, JSONObject release_info, String my_ver) throws JSONException {
        if (BuildConfig.IS_RELEASE == release_info.getBoolean("prerelease")) { // dont mix prerelease and release
            return true;
        }

        String rel_body = release_info.getString("body");
        Matcher matcher = updater_info_pattern.matcher(rel_body);
        if (!matcher.find()) {
            return true;
        }
        String base64_info_str = matcher.group(2);
        if (base64_info_str == null) {
            return true;
        }
        base64_info_str = base64_info_str.replace('=', '+');
        byte[] info_str_bytes;
        try {
            info_str_bytes = Base64.decode(base64_info_str, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException e) {
            return true;
        }
        String info_str = new String(info_str_bytes, StandardCharsets.UTF_8);
        String rel_ver;
        try {
            JSONObject info = new JSONObject(info_str);

            String min_update_ver = info.getString("min_update_ver");

            if (!is_version_string_valid(min_update_ver)) {
                return true;
            }

            if (MyUtils.compare_versions(min_update_ver, my_ver) > 0) {
                return true;
            }

            rel_ver = info.getString("ver");
            if (!is_version_string_valid(rel_ver)) {
                return true;
            }

            if (BuildConfig.IS_RELEASE == info.has("pre_rel")) { // dont mix release and prerelease
                return true;
            }

            if (MyUtils.compare_versions(rel_ver, latest.ver) <= 0) {
                return false; // only valid reason to not check all releases
            }
        } catch (JSONException | NumberFormatException e) {
            return true;
        }

        JSONArray assets = release_info.getJSONArray("assets");
        String apk_url = null;
        String apk_sig_url = null;

        for (int j = 0; j < assets.length(); j++) {
            JSONObject asset = assets.getJSONObject(j);
            String asset_name = asset.getString("name");

            if (asset_name.endsWith(".apk")) {
                apk_url = asset.getString("browser_download_url");
            }
            if (asset_name.endsWith(".apk.sig")) {
                apk_sig_url = asset.getString("browser_download_url");
            }
        }

        if (apk_url == null || apk_sig_url == null) {
            return true;
        }

        latest.ver = rel_ver;
        latest.apk_url = apk_url;
        latest.apk_sig_url = apk_sig_url;
        latest.base64_info = base64_info_str;

        return false;
    }

    public UpdateInfo get_latest_update_info() throws IOException, JSONException {
        String my_ver = MyUtils.my_package_version;
        RSAPublicKey my_pub_key = MyUtils.my_package_pub_key;

        int sig_bits = Integer.highestOneBit(my_pub_key.getModulus().bitLength() - 1) << 1;

//        if(true) {
//            my_ver = "0.9";
//        }

        UpdateInfo latest = new UpdateInfo();
        latest.ver = my_ver;

        MyUtils.HttpResponse resp = MyUtils.do_http_req_url_encoded_raw("https://api.github.com/repos/sagimor6/WoltBillSplitter/releases/latest", "GET", MyUtils.userAgent, new HashMap<>(), new HashMap<>());
        if (resp.code >= 500 && resp.code < 600) {
            throw new IOException("github server error " + resp.code + " " + resp.content);
        }

        boolean should_check_all_releases;
        if (resp.code == 200) {
            JSONObject release_info = new JSONObject(resp.content);
            should_check_all_releases = update_process_single_release_record(latest, release_info, my_ver);
        } else if (resp.code == 404) {
            should_check_all_releases = true;
        } else {
            throw new RuntimeException("unexpected github http code " + resp.code);
        }

        if (should_check_all_releases) {
            final int MAX_PER_PAGE = 100;
            int page = 0;

            JSONArray releases;
            do {
                HashMap<String, String> args = new HashMap<>();
                args.put("per_page", "" + MAX_PER_PAGE);
                args.put("page", "" + page);
                resp = MyUtils.do_http_req_url_encoded_raw("https://api.github.com/repos/sagimor6/WoltBillSplitter/releases", "GET", MyUtils.userAgent, new HashMap<>(), args);
                if (resp.code >= 500 && resp.code < 600) {
                    throw new IOException("github server error " + resp.code + " " + resp.content);
                }
                if (resp.code != 200) {
                    throw new RuntimeException("unexpected github http code " + resp.code);
                }

                releases = new JSONArray(resp.content);

                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release_info = releases.getJSONObject(i);
                    update_process_single_release_record(latest, release_info, my_ver);
                }

                page++;
            } while(releases.length() == MAX_PER_PAGE); // TODO: maybe change this to (length != 0) so if github change the max, we will still go through all
        }

        if (latest.ver.equals(my_ver)) {
            last_time_new_update_wasnt_found = System.currentTimeMillis();
            save_state();
            return null;
        }

        if (!check_github_url(latest.apk_url) || !check_github_url(latest.apk_sig_url)) {
            throw new RuntimeException("wtf are these urls:" + latest.apk_url + " , " + latest.apk_sig_url);
        }

//        if (true) {
//            latest.apk_url = "http://172.28.65.176:11223/app/build/outputs/apk/release/app-release.apk";
//            latest.apk_sig_url = "http://172.28.65.176:11224/app/build/outputs/apk/release/app-release.apk.sig";
//        }

        byte[] signature = new byte[sig_bits/8];

        URLConnection connection = new URL(latest.apk_sig_url).openConnection();
        connection.setDoOutput(false);
        connection.setDoInput(true);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestProperty("User-Agent", MyUtils.userAgent);
        connection.connect();
        try (InputStream inputStream = connection.getInputStream()) {
            int num_read = 0;
            while (num_read < signature.length) {
                int inc = inputStream.read(signature, num_read, signature.length - num_read);
                if (inc < 0) {
                    break;
                }
                num_read += inc;
            }
            if (num_read != signature.length) {
                throw new RuntimeException("sig len invalid");
            }
            if (inputStream.read() >= 0) {
                throw new RuntimeException("sig len invalid");
            }
        }

        latest.apk_signature = signature;

        return latest;
    }

    public void clear_update_session_state(Context ctx) {
        update_session_id = -1;
        was_just_before_session_create = false;
        save_state();

        NotificationManagerCompat.from(ctx).cancel(NotificationId.UPDATE_USER_ACTION_NEEDED);
    }

    public void clear_update_download_state() {

        update_num_bytes_written = 0;
        update_download_validator = null;
        update_size = -1;
        update_is_validated = false;

        save_state();
    }

    public void abandon_update_session(Context ctx) {
        PackageInstaller packageInstaller = ctx.getPackageManager().getPackageInstaller();
        if (update_session_id >= 0) {
            packageInstaller.abandonSession(update_session_id);
            clear_update_session_state(ctx);
            clear_update_download_state();
            // TODO: cancel the notification as well
        }
    }

    public boolean check_and_install_updates(Context ctx, UpdateInfo new_update_info) throws IOException {
        PackageInstaller packageInstaller = ctx.getPackageManager().getPackageInstaller();
        get_current_install_session_id(ctx);

        if (update_session_id < 0) {
            clear_update_download_state();
        }

        update_finished = true; // don't let the update_success intents ruin the state

        if (update_info == null || !update_info.equals(new_update_info)) { // newer updates always cancels old ones
            // we must abandon the session cause if we wrote into the upgrade.apk we can't resize it, android uses posix_fallocate
            abandon_update_session(ctx);

            if (update_info != new_update_info) {
                if (update_info == null) {
                    did_user_give_consent_downloading_on_metered = false;
                }
                update_info = new_update_info;
                save_state();
            }
        }

        if (update_info == null) {
            return true;
        }

        //DownloadManager a;

        boolean finished_download = update_size != -1 && update_num_bytes_written == update_size;

        HttpURLConnection con = null;
        if (!finished_download) {
            if (update_num_bytes_written > update_size) {
                // wtf?
            }

            if (!did_user_give_consent_downloading_on_metered) {
                ConnectivityManager connectivityManager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (connectivityManager.isActiveNetworkMetered()) {
                    if (last_time_new_update_wasnt_found < 0 || last_time_new_update_wasnt_found + 24*60*60*1000 <= System.currentTimeMillis()) {
                        Intent allowIntent = new Intent(ctx, NotificationBroadcastReceiver.class);
                        allowIntent.putExtra("notification_id", NotificationId.UPDATE_ALLOW_ON_METERED);
                        allowIntent.putExtra("btn", "allow");
                        PendingIntent allowPendingIntent = PendingIntent.getBroadcast(ctx, PendingIntentRequestCode.UPDATE_ALLOW_ON_METERED, allowIntent, PendingIntent.FLAG_IMMUTABLE);

                        Intent denyIntent = new Intent(ctx, NotificationBroadcastReceiver.class);
                        denyIntent.putExtra("notification_id", NotificationId.UPDATE_ALLOW_ON_METERED);
                        denyIntent.putExtra("btn", "deny");
                        PendingIntent denyPendingIntent = PendingIntent.getBroadcast(ctx, PendingIntentRequestCode.UPDATE_DENY_ON_METERED, denyIntent, PendingIntent.FLAG_IMMUTABLE);

                        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, UPDATE_NOTIFICATION_CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle("Update over metered network")
                                .setContentText("An update is available, please allow us to download it over a metered connection.")
                                .setPriority(UPDATE_NOTIFICATION_CHANNEL_PRIORITY)
                                .addAction(ResourcesCompat.ID_NULL, "ALLOW", allowPendingIntent)
                                .addAction(ResourcesCompat.ID_NULL, "DENY", denyPendingIntent)
                                .setOnlyAlertOnce(true)
                                .setAutoCancel(false);

                        Notification notification = builder.build();

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            NotificationManagerCompat.from(ctx).notify(NotificationId.UPDATE_ALLOW_ON_METERED, notification);
                        }
                    }
                    return false;
                }
            }
            NotificationManagerCompat.from(ctx).cancel(NotificationId.UPDATE_ALLOW_ON_METERED);

            // "http://172.28.65.176:11223/app/build/outputs/apk/release/app-release.apk"
            con = (HttpURLConnection) new URL(update_info.apk_url).openConnection();
            boolean expecting_only_200 = false;
            if (update_num_bytes_written != 0 && update_download_validator != null) {
                con.setRequestProperty("Range", update_num_bytes_written + "-");
                con.setRequestProperty("If-Range", update_download_validator);
            } else {
                expecting_only_200 = true;
            }

            con.setDoInput(true);
            con.setDoOutput(false);
            con.setRequestProperty("User-Agent", MyUtils.userAgent);

            con.setReadTimeout(2000);
            con.setConnectTimeout(2000);

            con.connect();

            switch (con.getResponseCode()) {
                case HttpURLConnection.HTTP_OK:
                    // even if we don't abandon, the verification will fail and we will restart the process
                    // and the need to abandon is an extremely unlikely scenario
                    // abandon_update_session(ctx);
                    update_num_bytes_written = 0;
                    String validator;
                    validator = con.getHeaderField("ETag");
                    if (validator == null || validator.startsWith("W/")) {
                        validator = con.getHeaderField("Last-Modified");
                    }
                    update_download_validator = validator;
                    update_size = con.getHeaderFieldLong("Content-Length", -1);
                    save_state();
                    break;
                case HttpURLConnection.HTTP_PARTIAL:
                    if (expecting_only_200) {
                        throw new IOException("Invalid response code " + con.getResponseCode());
                    }
                    break;
                default:
                    throw new IOException("Invalid response code " + con.getResponseCode());
            }
        }

        create_install_session_id(ctx);

        try (PackageInstaller.Session session = packageInstaller.openSession(update_session_id)) {

            if (!finished_download) {
                try (OutputStream outputStream = session.openWrite("upgrade.apk", update_num_bytes_written, update_size)) {

                    byte[] buffer = new byte[64 * 1024];
                    long total_num_written = 0;
                    try (InputStream inputStream = con.getInputStream()) {
                        while (true) {
                            int num_read = inputStream.read(buffer);
                            if (num_read < 0) {
                                break;
                            }
                            outputStream.write(buffer, 0, num_read);
                            total_num_written += num_read;
                        }
                    } finally {
                        session.fsync(outputStream); // fsync so if we reboot here it stays
                        update_num_bytes_written += total_num_written;
                        save_state();
                    }

                    if(update_size != -1 && update_num_bytes_written != update_size) {
                        // wtf
                    }
                    update_size = update_num_bytes_written;
                    did_user_give_consent_downloading_on_metered = false; // finished downloading, for new one, ask user again
                    save_state();
                }
            }

            if (!update_is_validated) {
                Signature signature;
                signature = Signature.getInstance("SHA512WithRSA");
                signature.initVerify(MyUtils.my_package_pub_key);

                byte[] buffer = new byte[64 * 1024];

                signature.update((byte)0);
                signature.update((byte)0); // EOC der, so this can't be interpreted even by accident as der
                signature.update("INFO_AND_APK_SIG".getBytes(StandardCharsets.US_ASCII));
                signature.update((byte)0);
                signature.update(update_info.base64_info.getBytes(StandardCharsets.US_ASCII));
                signature.update((byte)0);

                try (InputStream inputStream = session.openRead("upgrade.apk")) {
                    while(true) {
                        int num_read = inputStream.read(buffer);
                        if (num_read < 0) {
                            break;
                        }
                        signature.update(buffer, 0, num_read);
                    }
                }

                if (!signature.verify(update_info.apk_signature)) {
                    abandon_update_session(ctx); // TODO: we hold a handle while abandoning -> it looks android deals with it
                    throw new IOException("Package verification failed!"); // TODO: is this how we want to handle?
                }

                update_is_validated = true;
                save_state();
            }

            Intent intent = new Intent(ctx, UpdaterBroadcastReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(ctx, PendingIntentRequestCode.UPDATE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pendingIntent.getIntentSender());
        } catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    void on_update_failed(Context ctx) {
        // no need to clear state cause session is destroyed
        // which will cause the download state and all to be invalidated
    }

    void on_update_success(Context ctx) {
        // no need to clear state cause session is destroyed
        // which will cause the download state and all to be invalidated
    }

    public static final String UPDATE_NOTIFICATION_CHANNEL_ID = "update_chan_id";
    public static final int UPDATE_NOTIFICATION_CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_HIGH;
    public static final int UPDATE_NOTIFICATION_CHANNEL_PRIORITY = NotificationCompat.PRIORITY_HIGH;

    public static final class NotificationId {
        public static final int UPDATE_USER_ACTION_NEEDED = 1;
        public static final int UPDATE_ALLOW_ON_METERED = 2;
    }

    public static final class PendingIntentRequestCode {
        public static final int UPDATE = 1;
        public static final int UPDATE_NOTIFICATION = 2;
        public static final int UPDATE_ALLOW_ON_METERED = 3;
        public static final int UPDATE_DENY_ON_METERED = 4;
    }

    public static void create_all_notification_channels(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel updateNotificationChannel = new NotificationChannel(UPDATE_NOTIFICATION_CHANNEL_ID, "Update", UPDATE_NOTIFICATION_CHANNEL_IMPORTANCE);
            updateNotificationChannel.setDescription("Update notifications");

            NotificationManager notificationManager = ctx.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(updateNotificationChannel);
        }
    }

    void on_update_user_action_needed(Context ctx, Intent intent) {
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        //ctx.startActivity(intent);

        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, PendingIntentRequestCode.UPDATE_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("Click to update")
                .setContentText("An update has been downloaded, click here to install.")
                .setPriority(UPDATE_NOTIFICATION_CHANNEL_PRIORITY)
                // Set the intent that fires when the user taps the notification.
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // we don't have permission to post notifications
            return;
        }
        NotificationManagerCompat.from(ctx).notify(NotificationId.UPDATE_USER_ACTION_NEEDED, notification);
    }

}
