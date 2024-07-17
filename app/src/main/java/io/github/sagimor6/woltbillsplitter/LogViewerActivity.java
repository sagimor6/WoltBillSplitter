package io.github.sagimor6.woltbillsplitter;

import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class LogViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_log_viewer);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        File dir = getExternalFilesDir(null);

        File f = new File(dir, "my_log.txt");
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            FileInputStream reader1 = new FileInputStream(f);
            while(true) {
                int num = reader1.read(buf);
                if (num < 0) {
                    break;
                }

                os.write(buf, 0, num);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String all_log;

        try {
            all_log = os.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        ((TextView)findViewById(R.id.log_text_box)).setText(all_log);

        ScrollView scrollView = (ScrollView)findViewById(R.id.scrollView);
        scrollView.post(() -> {
            scrollView.fullScroll(View.FOCUS_DOWN);
        });

        //((TextView)findViewById(R.id.log_text_box)).setMovementMethod(new ScrollingMovementMethod());
        //((TextView)findViewById(R.id.log_text_box)).setScroller(new OverScroller(this));
    }
}