package io.github.sagimor6.woltbillsplitter;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MyLogger {

    private static FileWriter file_writer = null;
    private static final Object mutex = new Object();

    public static void setup_logger(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);

        File f = new File(dir, "my_log.txt");
        try {
            file_writer = new FileWriter(f, true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void clear_log(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);

        File f = new File(dir, "my_log.txt");

        synchronized (mutex) {
            try {
                file_writer.close();
                file_writer = null;

                f.delete();

                setup_logger(ctx);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void e(String msg) {
        String cur_time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(new Date());
        String log_entry = "[" + cur_time + "]: " + msg + "\n";
        synchronized (mutex) {
            try {
                file_writer.write(log_entry);
                file_writer.flush();
                Log.e("", msg);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void e(String msg, Throwable e) {
        e(msg + "\n" + MyUtils.exception_to_string(e));
    }

    public static void e(Throwable e) {
        e(MyUtils.exception_to_string(e));
    }

}
