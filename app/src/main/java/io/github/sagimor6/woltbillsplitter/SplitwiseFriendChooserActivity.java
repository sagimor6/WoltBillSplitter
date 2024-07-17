package io.github.sagimor6.woltbillsplitter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class SplitwiseFriendChooserActivity extends AppCompatActivity {

    private static final Pattern email_pattern = Pattern.compile("^(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])$");

    private static class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
        Consumer<Bitmap> on_finished;

        public DownloadImageTask(Consumer<Bitmap> _on_finished) {
            on_finished = _on_finished;
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
            on_finished.accept(result);
        }


    }

    private static class SplitFriend {
        public String email;
        public String name;

        public Bitmap friend_image;
        public ImageView image_view;

        public SplitFriend(JSONObject obj) throws JSONException {
            email = obj.getString("email");
            String first_name = obj.getString("first_name");
            if (obj.isNull("last_name")) {
                name = first_name;
            } else {
                name = first_name + " " + obj.getString("last_name");
            }

            new DownloadImageTask(bitmap -> {
                synchronized (this) {
                    friend_image = bitmap;
                    if (image_view != null) {
                        image_view.setImageBitmap(friend_image);
                        image_view = null;
                    }
                }
            }).executeOnExecutor(MyUtils.IMAGE_LOADER_EXECUTOR, obj.getJSONObject("picture").getString("medium"));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splitwise_friend_chooser);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        String wolt_id = getIntent().getStringExtra("wolt_id");
        String name = getIntent().getStringExtra("name");
        String image_url = getIntent().getStringExtra("image_url");

        ((TextView)findViewById(R.id.unk_user_name)).setText(name);

        ImageView imageView = ((ImageView)findViewById(R.id.unk_user_image));

        new DownloadImageTask(bitmap -> {
            imageView.setImageBitmap(bitmap);
        }).executeOnExecutor(MyUtils.IMAGE_LOADER_EXECUTOR, image_url);

        ArrayAdapter<SplitFriend> adapter = new ArrayAdapter<SplitFriend>(this, 0) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.splitwise_friend_list_entry, parent, false);
                } else {
                    SplitFriend prev_friend = (SplitFriend) convertView.getTag();
                    synchronized (prev_friend) {
                        prev_friend.image_view = null;
                    }
                }

                SplitFriend friend = getItem(position);
                convertView.setTag(friend);

                ((TextView) convertView.findViewById(R.id.name_box)).setText(friend.name);
                ((TextView) convertView.findViewById(R.id.email_box)).setText(friend.email);

                ImageView imageView = ((ImageView) convertView.findViewById(R.id.profile_pic));
                synchronized (friend) {
                    if (friend.friend_image == null) {
                        friend.image_view = imageView;
                    }
                    imageView.setImageBitmap(friend.friend_image);
                }

                return convertView;
            }
        };

        ListView listView = (ListView)findViewById(R.id.friends_list_view);
        listView.setAdapter(adapter);

        EditText email_edit_box = (EditText)findViewById(R.id.email_edit_box);

        listView.setOnItemClickListener((adapterView, view, i, l) -> {
            SplitFriend friend = (SplitFriend) adapterView.getItemAtPosition(i);
            email_edit_box.setText(friend.email);
        });

        AppCtx app_ctx = MyUtils.get_app_ctx(this);

        findViewById(R.id.set_user_email_btn).setOnClickListener(view -> {
            String email = email_edit_box.getText().toString();
            email = email.trim();
            if (email_pattern.matcher(email).matches()) {
                synchronized (app_ctx) {
                    app_ctx.known_users.put(wolt_id, email);
                    app_ctx.save_state();
                }
            } else {
                synchronized (app_ctx) {
                    app_ctx.known_users.remove(wolt_id);
                    app_ctx.save_state();
                }
            }

            finish();
        });


        new Thread(() -> {
            ArrayList<SplitFriend> split_friends_list = new ArrayList<>();
            SplitFriend[] split_friends;

            try {
                JSONArray friends;
                synchronized (app_ctx) {
                    friends = app_ctx.split_user.get_friend_list();
                }
                for (int i = 0; i < friends.length(); i++) {
                    JSONObject friend_obj = friends.getJSONObject(i);
                    if (friend_obj.getString("email").endsWith("@wolt.invalid")) {
                        continue;
                    }
                    split_friends_list.add(new SplitFriend(friend_obj));
                }
            } catch (JSONException | IOException e) {
                throw new RuntimeException(e);
            }

            split_friends = split_friends_list.toArray(new SplitFriend[0]);

            Arrays.sort(split_friends, Comparator.comparing(u -> u.name));

            runOnUiThread(() -> {
                adapter.addAll(split_friends);
            });

        }).start();

    }
}