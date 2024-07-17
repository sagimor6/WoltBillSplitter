package io.github.sagimor6.woltbillsplitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

public class UpdaterBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast.

        Log.e("", "got " + intent);

        String action = intent.getAction();

        AppCtx app_ctx = MyUtils.get_app_ctx(context);

        // we don't synchronize here, it is the responsibility of appctx to do it.
        // this is because we are running on main ui thread.

        if (action == null) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -2);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    Log.e("", "pending action");
                    Intent conf_intent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (conf_intent != null) {
                        app_ctx.on_update_user_action_needed(context, conf_intent);
                    }
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    MyLogger.e("update success!");
                    app_ctx.on_update_success(context);
                    break;
                default:
                    MyLogger.e("update failed!");
                    app_ctx.on_update_failed(context);
                    break;
            }
        } else {
            if (action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)) {
                MyLogger.e("update success, package replaced!");
                app_ctx.on_update_success(context);
            }
        }

    }
}