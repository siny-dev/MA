package com.siny.mobilebasic.modules;

import android.app.Activity;
import android.content.pm.PackageInstaller;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Receives PackageInstaller's callback and hands its approval screen to Android. */
public final class UpdateStatusActivity extends Activity {
    public static final String ACTION_INSTALL_RESULT="com.siny.mobilebasic.INSTALL_RESULT";

    @Override protected void onCreate(Bundle state){super.onCreate(state);handle(getIntent());}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}

    private void handle(Intent intent){
        if(intent==null||!ACTION_INSTALL_RESULT.equals(intent.getAction())){finish();return;}
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirmation;
            if(Build.VERSION.SDK_INT>=33)confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class);
            else confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null){confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(confirmation);}
        }else if(status==PackageInstaller.STATUS_SUCCESS){
            new AutoUpdateModule(this,new SecureSettings(this,com.siny.mobilebasic.AppIdentity.APP_ID)).clearPendingUpdateAfterSuccess();
            Toast.makeText(this,"업데이트를 적용했습니다.",Toast.LENGTH_LONG).show();
        }else{
            Toast.makeText(this,"Android가 업데이트 설치를 완료하지 못했습니다.",Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
