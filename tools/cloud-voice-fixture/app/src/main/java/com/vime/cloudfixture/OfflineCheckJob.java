package com.vime.cloudfixture;

import android.app.job.*;
import android.content.*;
import android.os.*;
import android.util.Log;

/** Internal cloud test driver. Uses fixed public fixtures, no microphone or network permission. */
public final class OfflineCheckJob extends JobService {
    private static final int ID=26008;
    private static final String TAG="JianyiOffline";
    private ResultReceiver receiver;
    private String nonce;
    private JobParameters running;
    private void grantFixtures(){
        // As in the foreground driver, the explicit read grant also makes this helper
        // visible to the target package's signature guard on Android 11 and later.
        for(String id:new String[]{"model","zh","en"})grantUriPermission("com.vime.ime",android.net.Uri.parse("content://com.vime.cloudfixture.documents/document/"+id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private void revokeFixtures(){
        for(String id:new String[]{"model","zh","en"})revokeUriPermission("com.vime.ime",android.net.Uri.parse("content://com.vime.cloudfixture.documents/document/"+id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    public static void schedule(Context context){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(ID)!=null)return;
        int result=scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(context,OfflineCheckJob.class))
            .setMinimumLatency(500).setOverrideDeadline(2000).build());
        Log.i(TAG,"schedule="+result+" helper=0.1.9");
    }
    @Override public boolean onStartJob(JobParameters parameters){
        running=parameters;
        try{
            var manager=getPackageManager();
            ComponentName target=new ComponentName("com.vime.ime.test","com.vime.ime.OfflineVoiceInstrumentation");
            if(!manager.getInstrumentationInfo(target,0).targetPackage.equals("com.vime.ime") ||
                manager.checkSignatures(getPackageName(),"com.vime.ime")!=android.content.pm.PackageManager.SIGNATURE_MATCH ||
                manager.checkSignatures(getPackageName(),"com.vime.ime.test")!=android.content.pm.PackageManager.SIGNATURE_MATCH)throw new IllegalStateException("Package/signature guard");
            grantFixtures();nonce=java.util.UUID.randomUUID().toString();
            Bundle args=new Bundle();args.putBoolean("vimeControlledCloud",true);args.putString("resultNonce",nonce);
            receiver=new ResultReceiver(new Handler(Looper.getMainLooper())){
                @Override protected void onReceiveResult(int code,Bundle data){
                    if(data==null || !nonce.equals(data.getString("resultNonce")))return;
                    String report=data.getString("offlineReport");
                    if(report==null || report.length()>32768)return;
                    try{
                        var object=new org.json.JSONObject(report);
                        var rows=object.getJSONArray("cases");int passed=0;
                        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getBoolean("pass"))passed++;
                        try(var output=openFileOutput("last-job-offline-checks.json",MODE_PRIVATE)){output.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                        Log.i(TAG,"RESULT "+passed+"/"+rows.length()+" "+object.getJSONObject("observations").toString());
                        for(int i=0;i<rows.length();i++)Log.i(TAG,"CASE "+rows.getJSONObject(i).toString());
                        Log.i(TAG,"END helper=0.1.9");
                    }catch(Exception e){Log.e(TAG,"Invalid result: "+e.getClass().getSimpleName());}
                    revokeFixtures();receiver=null;JobParameters completed=running;running=null;
                    if(completed!=null)jobFinished(completed,false);
                }
            };
            Parcel parcel=Parcel.obtain();
            try{receiver.writeToParcel(parcel,0);parcel.setDataPosition(0);args.putParcelable("resultReceiver",ResultReceiver.CREATOR.createFromParcel(parcel));}
            finally{parcel.recycle();}
            if(!startInstrumentation(target,null,args))throw new IllegalStateException("Start rejected");
            Log.i(TAG,"START helper=0.1.9 fixed-public-fixtures");return true;
        }catch(Exception e){Log.e(TAG,"START FAILED "+e.getClass().getSimpleName()+": "+e.getMessage());revokeFixtures();receiver=null;running=null;return false;}
    }
    @Override public boolean onStopJob(JobParameters parameters){revokeFixtures();running=null;receiver=null;Log.w(TAG,"STOP helper=0.1.9");return false;}
}
