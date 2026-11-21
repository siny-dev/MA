package com.siny.mobilebasic.modules;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.siny.mobilebasic.AppIdentity;

public final class UpdateJobService extends JobService {
    private volatile Thread worker;

    @Override public boolean onStartJob(JobParameters params){
        worker=new Thread(()->{
            try{
                SecureSettings settings=new SecureSettings(this,AppIdentity.APP_ID);
                new AutoUpdateModule(this,settings).checkAndDownload();
            }finally{
                if(!Thread.currentThread().isInterrupted())jobFinished(params,false);
            }
        },"mobile-autoupdate-background");
        worker.setDaemon(true);worker.start();return true;
    }

    @Override public boolean onStopJob(JobParameters params){Thread active=worker;if(active!=null)active.interrupt();return true;}
}
