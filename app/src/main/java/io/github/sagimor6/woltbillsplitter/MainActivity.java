package io.github.sagimor6.woltbillsplitter;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private String _access_token;
    private String _refresh_token;
    private long _refresh_time;

    private String _split_access_token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActivityResultLauncher<Intent> wolt_launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == RESULT_OK) {
                String wolt_token = res.getData().getStringExtra("wolt_token");

                AppCtx app_ctx = MyUtils.get_app_ctx(this);

                WoltUser new_wolt_user;
                try {
                    new_wolt_user = new WoltUser(wolt_token, System.currentTimeMillis());
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }

                synchronized (app_ctx) {
                    app_ctx.set_wolt_user(new_wolt_user);
                }
            }
        });


        ActivityResultLauncher<Intent> split_launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == RESULT_OK) {
                String split_token = res.getData().getStringExtra("split_token");

                AppCtx app_ctx = MyUtils.get_app_ctx(this);

                SplitwiseUser new_split_user;
                try {
                    new_split_user = new SplitwiseUser(split_token);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }

                synchronized (app_ctx) {
                    app_ctx.set_splitwise_user(new_split_user);
                }
            }
        });

        ActivityResultLauncher<String> requestPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (isGranted) {
                        // Permission is granted. Continue the action or workflow in your
                        // app.
                    } else {
                        // Explain to the user that the feature is unavailable because the
                        // feature requires a permission that the user has denied. At the
                        // same time, respect the user's decision. Don't link to system
                        // settings in an effort to convince the user to change their
                        // decision.
                    }
                });


        findViewById(R.id.clear_user_btn).setOnClickListener(view -> {
            AppCtx app_ctx = MyUtils.get_app_ctx(this);
            synchronized (app_ctx) {
                app_ctx.set_wolt_user(null);
                app_ctx.set_splitwise_user(null);
            }
        });

        findViewById(R.id.clear_friends_btn).setOnClickListener(view -> {
            AppCtx app_ctx = MyUtils.get_app_ctx(this);
            synchronized (app_ctx) {
                app_ctx.known_users.clear();
                app_ctx.in_processing_orders.clear();
                app_ctx.finished_orders.clear();
                app_ctx.unpaid_orders.clear();

                app_ctx.save_state();
            }
        });


        findViewById(R.id.clear_orders).setOnClickListener(view -> {
            AppCtx app_ctx = MyUtils.get_app_ctx(this);
            synchronized (app_ctx) {
                app_ctx.in_processing_orders.clear();
                app_ctx.finished_orders.clear();
                app_ctx.unpaid_orders.clear();

                app_ctx.save_state();
            }
        });


        findViewById(R.id.new_splitwise_user_btn).setOnClickListener(view -> {

            EditText editText = new EditText(this);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Splitwise app secret")
                    .setMessage("Enter the app secret given to you by the developer.")
                    .setView(editText)
                    .setPositiveButton("OK", (dialogInterface, i) -> {
                        String client_secret = editText.getText().toString().trim();

                        split_launcher.launch(new Intent(this, CredFetcher.class)
                                .putExtra("auth_type", "splitwise")
                                .putExtra("split_client_secret", client_secret));
                    })
                    .setNegativeButton("Cancel", null)
                    .create();

            dialog.show();
        });

        findViewById(R.id.new_wolt_user_google_btn).setOnClickListener(view -> {
            wolt_launcher.launch(new Intent(this, CredFetcher.class).putExtra("auth_type", "wolt"));
        });

        findViewById(R.id.new_wolt_user_email_btn).setOnClickListener(view -> {


            EditText editText = new EditText(this);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Wolt email login")
                    .setMessage("Open your web browser and go to wolt.com, then login by email\nand paste here the link which was sent to your email address.")
                    .setView(editText)
                    .setPositiveButton("OK", (dialogInterface, i) -> {
                        String email_link = editText.getText().toString();
                        Thread th = new Thread(() -> {
                            String email_token = WoltUser.get_email_token_from_email_url(email_link);
                            String wolt_token;
                            try {
                                wolt_token = WoltUser.get_wolt_token_from_email_token(email_token);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            WoltUser new_wolt_user;
                            try {
                                new_wolt_user = new WoltUser(wolt_token, System.currentTimeMillis());
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }

                            AppCtx app_ctx = MyUtils.get_app_ctx(this);
                            synchronized (app_ctx) {
                                app_ctx.set_wolt_user(new_wolt_user);
                            }
                        });
                        th.start();
                        try {
                            th.join();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create();

            dialog.show();
        });

        findViewById(R.id.clear_browser_cache_btn).setOnClickListener(view -> {
            WebStorage.getInstance().deleteAllData();

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();

            WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(getApplicationContext());
            webViewDatabase.clearFormData();
            webViewDatabase.clearHttpAuthUsernamePassword();

            WebView webView = new WebView(getApplicationContext());
            webView.clearCache(true);
        });

        findViewById(R.id.start_btn).setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                }
            }

            {
                if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

                    startActivity(intent);
                }
            }

            start_work(getApplicationContext());

        });

        findViewById(R.id.stop_btn).setOnClickListener(view -> {
            stop_all_work(this);
        });

        findViewById(R.id.clear_state_btn).setOnClickListener(view -> {
            AppCtx app_ctx;
            try {
                app_ctx = MyUtils.get_app_ctx(this);
            } catch (Throwable e) {
                app_ctx = null;
            }

            if (app_ctx != null) {
                stop_all_work(this);
                synchronized (app_ctx) {
                    MyUtils.delete_app_ctx(this);
                }
                app_ctx = null;
            } else {
                MyUtils.delete_app_ctx(this);
            }
        });

        findViewById(R.id.unknown_friends_btn).setOnClickListener(view -> {
            startActivity(new Intent(this, UnknownUsersActivity.class));
        });

        findViewById(R.id.show_log).setOnClickListener(view -> {
            startActivity(new Intent(this, LogViewerActivity.class));
        });

        findViewById(R.id.clear_log).setOnClickListener(view -> {
            MyLogger.clear_log(this);
            MyLogger.e("log cleared");
        });

        findViewById(R.id.update).setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                }
            }

            {
                if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

                    startActivity(intent);
                }
            }

            new Thread(() -> {
                AppCtx app_ctx = MyUtils.get_app_ctx(this);
                try {
                    synchronized (app_ctx) {
                        AppCtx.UpdateInfo updateInfo = app_ctx.get_latest_update_info();
                        app_ctx.check_and_install_updates(getApplicationContext(), updateInfo);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }).start();
        });

        MyLogger.e("main activity started");
    }

    public static class MyBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            MyLogger.e("MyBroadcastReceiver.onReceive " + action);

            if (action == null) {
                return;
            }

            switch (action) {
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_SCREEN_OFF:
                    enqueue_work(context, true);
                    break;
            }
        }
    }

    private static boolean needs_to_stop_work = false;
    private static final Object enqueue_work_lock = new Object();

    public static void stop_all_work(Context ctx) {
        synchronized (enqueue_work_lock) {
            needs_to_stop_work = true;
        }

        // TODO: make my_receiver a global?
        // TODO: move all this logic to RuntimeBroker
        synchronized (MyWorkerPeriodic.my_receiver) {
            if (MyWorkerPeriodic.is_recveiver_registered) {
                ctx.getApplicationContext().unregisterReceiver(MyWorkerPeriodic.my_receiver);
                MyWorkerPeriodic.is_recveiver_registered = false;
            }
        }

        try {
            WorkManager.getInstance(ctx.getApplicationContext()).cancelAllWork().getResult().get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void start_work(Context ctx) {
        WorkManager workManager = WorkManager.getInstance(ctx.getApplicationContext());
        PeriodicWorkRequest periodicWorkRequest = new PeriodicWorkRequest.Builder(MyWorkerPeriodic.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        synchronized (enqueue_work_lock) {
            needs_to_stop_work = false;
            workManager.enqueueUniquePeriodicWork("yaya2", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicWorkRequest);
        }


//            periodicWorkRequest = new PeriodicWorkRequest.Builder(MyWorkerPeriodic2.class, 1, TimeUnit.HOURS)
//                    .setConstraints(new Constraints.Builder()
//                            .build())
//                    .build();
//
//            workManager.enqueueUniquePeriodicWork("yaya3", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicWorkRequest);
    }

    public static void enqueue_work(Context ctx, boolean immediate) {
        WorkManager workManager = WorkManager.getInstance(ctx.getApplicationContext());

//        ListenableFuture<List<WorkInfo>> a = workManager.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.values()));
//        List<WorkInfo> b;
//        try {
//            b = a.get();
//        } catch (ExecutionException | InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        for (WorkInfo c : b) {
//            c.getState();
//        }


        OneTimeWorkRequest do_work = new OneTimeWorkRequest.Builder(MyWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setInitialDelay(immediate ? 0 : 1, TimeUnit.MINUTES)
                .build();


        synchronized (enqueue_work_lock) {
            if (!needs_to_stop_work) {
                try {
                    workManager.enqueueUniqueWork("yaya", ExistingWorkPolicy.REPLACE, do_work).getResult().get();
                } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static boolean do_work(Context ctx) {
        try {
            MyLogger.e("cycle start");
            AppCtx app_ctx = MyUtils.get_app_ctx(ctx.getApplicationContext());
            synchronized (app_ctx) {
                if (app_ctx.last_time_new_update_wasnt_found + 48 * 60 * 60 * 1000 <= System.currentTimeMillis()) {
                    MyLogger.e("it has been more than two days since last time updates where checked and no new updates, stopping work.");
                    return false;
                }
                app_ctx.do_single_cycle();
            }
            MyLogger.e("cycle done");
        } catch (IOException e) {
            // network exception probably
            MyLogger.e(e);
        } catch (Throwable e) {
            MyLogger.e(e);
            stop_all_work(ctx);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            MyLogger.e("current app bucket: " + ctx.getSystemService(UsageStatsManager.class).getAppStandbyBucket());
            MyLogger.e("isBackgroundRestricted: " + ctx.getSystemService(ActivityManager.class).isBackgroundRestricted());
            MyLogger.e("isIgnoringBatteryOptimizations: " + ctx.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(ctx.getPackageName()));
            MyLogger.e("isDeviceIdleMode: " + ctx.getSystemService(PowerManager.class).isDeviceIdleMode());
        }
        return true;
    }

    public static class MyWorker extends Worker {

        public static int count = 0;

        public MyWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @Override
        public void onStopped() {
            MyLogger.e("Worker.onStopped");
            super.onStopped();
        }

        @NonNull
        @Override
        public Result doWork() {
            MyLogger.e("work!");
            PowerManager powerManager = getApplicationContext().getSystemService(PowerManager.class);
            boolean should_requeue = powerManager.isInteractive();

            should_requeue &= do_work(getApplicationContext());

            if (should_requeue) {
                enqueue_work(getApplicationContext(), false);
            }

//            count++;
//            if (count <= 2) {
//                WorkManager workManager = WorkManager.getInstance(getApplicationContext());
//
//                OneTimeWorkRequest on_idle = new OneTimeWorkRequest.Builder(MyWorker.class)
//                        .setConstraints(new Constraints.Builder()
//                                .setRequiredNetworkType(NetworkType.CONNECTED)
//                                .build())
//                        .setInitialDelay(5000000, TimeUnit.MICROSECONDS)
//                        .build();
//                Operation a = workManager.enqueueUniqueWork("yaya", ExistingWorkPolicy.REPLACE, on_idle);
//                try {
//                    Thread.sleep(6000);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//                Operation.State b = a.getState().getValue();
//                Log.e("", "enqueue: " + (b == null ? "" : b.toString()));
//            }
            return Result.success();
        }
    }

    public static class MyWorkerPeriodic extends Worker {

        public static final MyBroadcastReceiver my_receiver = new MyBroadcastReceiver();
        public static volatile boolean is_recveiver_registered = false;
        public MyWorkerPeriodic(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            MyLogger.e("periodic work!");

            try {
                AppCtx app_ctx = MyUtils.get_app_ctx(getApplicationContext());
                synchronized (app_ctx) {
                    try {
                        AppCtx.UpdateInfo updateInfo = app_ctx.get_latest_update_info();
                        app_ctx.check_and_install_updates(getApplicationContext(), updateInfo);
                    } catch (IOException e) {
                        // probably network exception, continue with our lives
                        MyLogger.e(e);
                    }

                    if (app_ctx.last_time_new_update_wasnt_found + 48 * 60 * 60 * 1000 <= System.currentTimeMillis()) {
                        MyLogger.e("it has been more than two days since last time updates where checked and no new updates, stopping work.");
                        return Result.success();
                    }
                }
            } catch (Throwable e) {
                MyLogger.e(e);
                stop_all_work(getApplicationContext());
                return Result.success();
            }

            if (!is_recveiver_registered) {
                synchronized (my_receiver) {
                    if (!is_recveiver_registered && !needs_to_stop_work) {
                        IntentFilter flt = new IntentFilter();
                        flt.addAction(Intent.ACTION_SCREEN_ON);
                        flt.addAction(Intent.ACTION_SCREEN_OFF);
                        getApplicationContext().registerReceiver(my_receiver, flt);
                        is_recveiver_registered = true;
                    }
                }
            }

            enqueue_work(getApplicationContext(), true);

            return Result.success();
        }
    }

    public static class MyWorkerPeriodic2 extends Worker {

        public MyWorkerPeriodic2(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            MyLogger.e("notification worker started!");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel = new NotificationChannel("my channel id", "my channel id string", NotificationManager.IMPORTANCE_HIGH);
                notificationChannel.setDescription("my channel id desc");

                NotificationManager notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
                notificationManager.createNotificationChannel(notificationChannel);
            }

            Notification notification = new NotificationCompat.Builder(getApplicationContext(), "my channel id")
                    .setContentTitle("dummy1")
                    .setTicker("dummy2")
                    .setSmallIcon(IconCompat.createWithResource(getApplicationContext(), R.mipmap.ic_launcher))
                    .setOngoing(true)
                    .build();

            ForegroundInfo foregroundInfo = new ForegroundInfo(1, notification);
            try {
                setForegroundAsync(foregroundInfo).get();
                Thread.sleep(20000);
            } catch (Throwable e) {
                MyLogger.e(e);
            }

            MyLogger.e("notification worker finished!");

            return Result.success();
        }
    }
}