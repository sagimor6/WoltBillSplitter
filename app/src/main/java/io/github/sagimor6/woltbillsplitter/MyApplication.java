package io.github.sagimor6.woltbillsplitter;

import android.app.Application;
import android.util.Log;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        Log.e("", "Application onCreate!2");
        MyLogger.setup_logger(getApplicationContext());
        MyLogger.e("Application onCreate!");
        MyUtils.fill_static_info(getApplicationContext());
        AppCtx.create_all_notification_channels(getApplicationContext());
        super.onCreate();
    }

    @Override
    public void onTerminate() {
        MyLogger.e("Application onTerminate!");
        super.onTerminate();
    }
}
