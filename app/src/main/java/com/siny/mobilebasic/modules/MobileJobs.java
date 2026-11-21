package com.siny.mobilebasic.modules;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** OS-managed best-effort jobs; Android enforces a 15-minute floor for periodic background work. */
public final class MobileJobs {
    private static final int SYNC_JOB_ID=42001;
    private static final int UPDATE_JOB_ID=42002;
    private MobileJobs() { }

    public static void schedule(Context context){
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        boolean remoteSyncConfigured;
        try {
            remoteSyncConfigured = !new SecureSettings(context, com.siny.mobilebasic.AppIdentity.APP_ID)
                    .serverUrl().trim().isEmpty();
        } catch (RuntimeException e) {
            remoteSyncConfigured = false;
        }
        if(remoteSyncConfigured) scheduleOne(scheduler,context,SyncJobService.class,SYNC_JOB_ID,AutoSyncModule.BACKGROUND_INTERVAL_MS);
        else scheduler.cancel(SYNC_JOB_ID);
        scheduleOne(scheduler,context,UpdateJobService.class,UPDATE_JOB_ID,AutoUpdateModule.BACKGROUND_INTERVAL_MS);
    }

    private static void scheduleOne(JobScheduler scheduler,Context context,Class<?> service,int id,long interval){
        JobInfo job=new JobInfo.Builder(id,new ComponentName(context,service))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(interval,5L*60L*1000L)
                .setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(job);
    }
}
