package com.vime.cloudfixture;

/** Only the system's update broadcast for this disposable test package schedules the job. */
public final class ReplacedReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context,android.content.Intent intent){
        if(android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))OfflineCheckJob.schedule(context);
    }
}
