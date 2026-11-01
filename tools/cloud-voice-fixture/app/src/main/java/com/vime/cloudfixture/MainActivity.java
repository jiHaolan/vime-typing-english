package com.vime.cloudfixture;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.content.Intent;
import android.content.ComponentName;
import android.net.Uri;

/** Controlled cloud fixture: local sample playback and a disposable IME input field. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private TextView status;
    private EditText input;
    private static final String[] IDS={"model","zh","en"};
    private static final int CHECK_REVISION=9;
    private android.os.ResultReceiver reportReceiver;
    private void stop(){handler.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}}
    private int dp(int value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private void button(LinearLayout column,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());column.addView(b);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        ScrollView scroll=new ScrollView(this);scroll.setFitsSystemWindows(true);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(20),dp(20),dp(20),dp(20));scroll.addView(column);setContentView(scroll);
        TextView title=new TextView(this);title.setText("键忆云真机语音验证");title.setTextSize(24);column.addView(title);
        TextView help=new TextView(this);help.setText("播放固定合成样本，使用键忆输入法长按空格收音。此工具仅提供样本和测试输入框。");help.setTextSize(15);column.addView(help);
        status=new TextView(this);status.setText("先导入模型、选择离线语音，并准备好输入法麦克风权限。");column.addView(status);
        button(column,"2秒后播放中文样本",()->queue("zh.wav"));
        button(column,"2秒后播放英文样本",()->queue("en.wav"));
        button(column,"停止播放",()->{stop();status.setText("已停止");});
        button(column,"运行固定样本离线识别检查",this::runChecks);
        input=new EditText(this);input.setHint("识别文字应回到这里");input.setMinLines(3);input.setGravity(android.view.Gravity.TOP);column.addView(input);
        button(column,"清空测试输入框",()->input.setText(""));
        if(getPreferences(MODE_PRIVATE).getInt("autoChecksRevision",0)!=CHECK_REVISION){
            getPreferences(MODE_PRIVATE).edit().putInt("autoChecksRevision",CHECK_REVISION).apply();
            OfflineCheckJob.schedule(this);
            status.setText("已安排固定样本系统测试任务；结果写入本工具私有文件与 JianyiOffline 日志。此任务不使用麦克风。");
        }
    }
    private void runChecks(){
        try{
            var manager=getPackageManager();
            var component=new ComponentName("com.vime.ime.test","com.vime.ime.OfflineVoiceInstrumentation");
            if(!manager.getInstrumentationInfo(component,0).targetPackage.equals("com.vime.ime") ||
                manager.checkSignatures(getPackageName(),"com.vime.ime")!=android.content.pm.PackageManager.SIGNATURE_MATCH ||
                manager.checkSignatures(getPackageName(),"com.vime.ime.test")!=android.content.pm.PackageManager.SIGNATURE_MATCH)throw new Exception();
            String nonce=java.util.UUID.randomUUID().toString();getPreferences(MODE_PRIVATE).edit().putString("runNonce",nonce).apply();
            for(String id:IDS)grantUriPermission("com.vime.ime",Uri.parse("content://com.vime.cloudfixture.documents/document/"+id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Bundle args=new Bundle();args.putBoolean("vimeControlledCloud",true);args.putString("resultNonce",nonce);
            reportReceiver=new android.os.ResultReceiver(new Handler(Looper.getMainLooper())){
                @Override protected void onReceiveResult(int code,Bundle data){if(data!=null&&!isDestroyed())acceptReport(data.getString("resultNonce"),data.getString("offlineReport"));}
            };
            // Bundle must carry the framework class, not this app's anonymous subclass.
            android.os.Parcel parcel=android.os.Parcel.obtain();
            try{reportReceiver.writeToParcel(parcel,0);parcel.setDataPosition(0);args.putParcelable("resultReceiver",android.os.ResultReceiver.CREATOR.createFromParcel(parcel));}
            finally{parcel.recycle();}
            if(!startInstrumentation(component,null,args))throw new Exception();
            getPreferences(MODE_PRIVATE).edit().putInt("autoChecksRevision",CHECK_REVISION).apply();
            status.setText("正在检查固定模型、中英文识别、取消与输入引擎；这不是麦克风识别准确率测试。请等待结果返回。");
        }catch(Exception e){status.setText("无法开始。请安装同一签名的键忆候选版及专用检查APK。");releaseGrants();}
    }
    private void releaseGrants(){for(String id:IDS)revokeUriPermission("com.vime.ime",Uri.parse("content://com.vime.cloudfixture.documents/document/"+id),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
    private void acceptReport(String nonce,String report){
        if(nonce==null || !nonce.equals(getPreferences(MODE_PRIVATE).getString("runNonce","")) || report==null || report.length()>32768)return;
        try{
            var data=new org.json.JSONObject(report);var cases=data.getJSONArray("cases");var obs=data.getJSONObject("observations");int passed=0;StringBuilder failures=new StringBuilder();
            for(int i=0;i<cases.length();i++){var row=cases.getJSONObject(i);if(row.getBoolean("pass"))passed++;else failures.append("\n失败：").append(row.optString("name")).append(" ").append(row.optString("error"));}
            StringBuilder text=new StringBuilder("固定样本检查完成：").append(passed).append('/').append(cases.length()).append("项通过\n").append(obs.optString("abi")).append(" · 内存页 ").append(obs.optLong("page_bytes")/1024).append("KB\n");
            for(String language:new String[]{"zh","en"}){var sample=obs.optJSONObject(language);if(sample!=null)text.append(language.equals("zh")?"中文：":"English: ").append(sample.optString("text")).append("\n耗时 ").append(sample.optLong("milliseconds")).append("ms\n");}
            String hash=obs.optString("main_apk_sha256");text.append("主APK SHA256：\n");for(int i=0;i<hash.length();i+=32)text.append(hash.substring(i,Math.min(i+32,hash.length()))).append('\n');
            text.append("合成样本检查，不含麦克风验收。").append(failures);status.setText(text);
            try(var output=openFileOutput("last-offline-checks.json",MODE_PRIVATE)){output.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e){status.setText("检查结果格式异常，请查看测试日志。");}
        releaseGrants();
        getPreferences(MODE_PRIVATE).edit().remove("runNonce").apply();
        reportReceiver=null;
    }
    private void queue(String asset){
        stop();input.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
        status.setText("2秒后播放，请立即长按键盘空格，播放结束后松手。");
        handler.postDelayed(()->{
            try(var file=getAssets().openFd(asset)){
                player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                player.setDataSource(file.getFileDescriptor(),file.getStartOffset(),file.getLength());
                player.setOnPreparedListener(p->{status.setText("正在播放合成样本…");p.start();});
                player.setOnCompletionListener(p->{status.setText("样本播放结束，请松开空格");stop();});
                player.setOnErrorListener((p,what,extra)->{status.setText("测试音频无法播放");stop();return true;});
                player.prepareAsync();
            }catch(Exception e){status.setText("测试音频无法打开");stop();}
        },2000);
    }
    @Override public void onStop(){stop();super.onStop();}
}
