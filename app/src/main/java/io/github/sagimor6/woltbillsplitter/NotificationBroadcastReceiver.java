package io.github.sagimor6.woltbillsplitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class NotificationBroadcastReceiver extends BroadcastReceiver {

    public static class NotificationBroadcastReceiverWorker extends Worker {

        public NotificationBroadcastReceiverWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            AppCtx app_ctx = MyUtils.get_app_ctx(getApplicationContext());
            boolean changed_state = false;
            synchronized (app_ctx) {
                // if we are currently downloading something, approve this
                if (app_ctx.update_info != null && (app_ctx.update_size == -1 || app_ctx.update_num_bytes_written != app_ctx.update_size) && !app_ctx.did_user_give_consent_downloading_on_metered) {
                    app_ctx.did_user_give_consent_downloading_on_metered = true;
                    app_ctx.save_state();
                    changed_state = true;
                }
            }

            if (changed_state) {
                // TODO: refactor all of this plz
                MainActivity.start_work(getApplicationContext());
            }

            return Result.success();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast.
        if (intent.getAction() == null) {
            int notification_id = intent.getIntExtra("notification_id", -1);
            switch (notification_id) {
                case AppCtx.NotificationId.UPDATE_ALLOW_ON_METERED:
                    if ("allow".equals(intent.getStringExtra("btn"))) {
                        // we make a worker here, because the synchronized block can block for some time
                        // TODO: yes we need a major refactor in all the locking/saving system
                        WorkManager.getInstance(context).enqueueUniqueWork(
                                "update_on_metered_consent_worker",
                                ExistingWorkPolicy.REPLACE,
                                new OneTimeWorkRequest.Builder(NotificationBroadcastReceiverWorker.class)
                                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                        .build()
                        );
                    }
                    NotificationManagerCompat.from(context).cancel(AppCtx.NotificationId.UPDATE_ALLOW_ON_METERED);
                    break;
                default:
                    break;
            }
        }
    }
}