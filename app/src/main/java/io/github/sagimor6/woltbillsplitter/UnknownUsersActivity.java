package io.github.sagimor6.woltbillsplitter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class UnknownUsersActivity extends AppCompatActivity {

    private static final class UnknownUser {
        String first_name;
        String last_name;
        String uid;
        String image_url;
        String email;

        public UnknownUser() {}
    }

    private Bitmap[] bitmaps;
    private ImageView[] image_views;
    private final Object bitmap_lock = new Object();

    private class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
        int offset;

        public DownloadImageTask(int i) {
            this.offset = i;
        }


        protected Bitmap doInBackground(String... urls) {
            String urldisplay = urls[0];
            Bitmap mIcon11 = null;
            try {
                InputStream in = new java.net.URL(urldisplay).openStream();
                mIcon11 = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return mIcon11;
        }

        protected void onPostExecute(Bitmap result) {
            synchronized (bitmap_lock) {
                bitmaps[offset] = result;
                ImageView view = image_views[offset];
                if (view != null) {
                    view.setImageBitmap(result);
                    image_views[offset] = null; // we don't need him
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_unkown_users);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        AppCtx app_ctx = MyUtils.get_app_ctx(this);
        UnknownUser[] unknown_users;

        ArrayList<ArrayList<UnknownUser>> _res = new ArrayList<>();

        Thread th = new Thread(() -> {
            synchronized (app_ctx) {
                ArrayList<UnknownUser> res = new ArrayList<>();
                // TODO: known_users may contain more friends
                try {
                    JSONArray friends = app_ctx.get_wolt_friends();
                    for(int i = 0; i < friends.length(); i++) {
                        JSONObject friend = friends.getJSONObject(i);
                        String uid = friend.getString("user_id");
                        UnknownUser user = new UnknownUser();
                        user.uid = uid;
                        user.first_name = friend.getString("first_name");
                        user.last_name = friend.getString("last_name");
                        user.image_url = friend.getString("profile_picture_url");
                        user.email = app_ctx.known_users.get(uid);
                        res.add(user);
                    }
                } catch (JSONException | IOException e) {

                }
                _res.add(res);
            }
        });
        th.start();
        try {
            th.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        unknown_users = _res.get(0).toArray(new UnknownUser[0]);

        Arrays.sort(unknown_users, (u1, u2) -> {
            String u1_name = u1.first_name + " " + u1.last_name;
            String u2_name = u2.first_name + " " + u2.last_name;
            return u1_name.compareTo(u2_name);
        });

        bitmaps = new Bitmap[unknown_users.length];
        image_views = new ImageView[unknown_users.length];

        for(int i = 0; i < unknown_users.length; i++) {
            new DownloadImageTask(i).executeOnExecutor(MyUtils.IMAGE_LOADER_EXECUTOR, unknown_users[i].image_url);
        }

        ((ListView)findViewById(R.id.unknown_users_list_view)).setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return unknown_users.length;
            }

            @Override
            public Object getItem(int i) {
                return unknown_users[i];
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View view, ViewGroup viewGroup) {
                int prev_tag = -1;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.unknown_user_entry, viewGroup, false);
                } else {
                    prev_tag = (int)view.getTag();
                }

                view.setTag(i);

                UnknownUser user = unknown_users[i];

                ((TextView) view.findViewById(R.id.unk_user_name)).setText(user.first_name + " " + user.last_name);
                ((TextView) view.findViewById(R.id.is_email_known_box)).setVisibility(user.email == null ? View.VISIBLE : View.INVISIBLE);

                ImageView imageView = ((ImageView) view.findViewById(R.id.unk_user_image));
                synchronized (bitmap_lock) {
                    if(prev_tag >= 0) {
                        image_views[prev_tag] = null;
                    }
                    if (bitmaps[i] == null) {
                        image_views[i] = imageView;
                    }
                    imageView.setImageBitmap(bitmaps[i]);
                }

                view.setOnClickListener(view1 -> {
                    Intent intent = new Intent(getApplicationContext(), SplitwiseFriendChooserActivity.class);
                    intent.putExtra("wolt_id", user.uid);
                    intent.putExtra("name", user.first_name + " " + user.last_name);
                    intent.putExtra("image_url", user.image_url);
                    startActivity(intent);
                });

                return view;
            }
        });
    }
}