package com.siny.mobilebasic.modules;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.siny.mobilebasic.AppIdentity;
import com.siny.mobilebasic.storage.MobileStore;

public final class SyncJobService extends JobService {
    private volatile Thread worker;

    @Override public boolean onStartJob(JobParameters params){
        worker=new Thread(()->{
            MobileStore store=null;
            try{
                store=new MobileStore(this);
                SecureSettings settings=new SecureSettings(this,AppIdentity.APP_ID);
                new AutoSyncModule(this,store,settings).syncOnce();
            }finally{
                if(store!=null)store.close();
                if(!Thread.currentThread().isInterrupted())jobFinished(params,false);
            }
        },"mobile-autosync-background");
        worker.setDaemon(true);worker.start();return true;
    }

    @Override public boolean onStopJob(JobParameters params){Thread active=worker;if(active!=null)active.interrupt();return true;}
}
