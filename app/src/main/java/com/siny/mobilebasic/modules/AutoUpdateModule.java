package com.siny.mobilebasic.modules;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.siny.mobilebasic.AppIdentity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** Per-app Android updater. Android's package installer always remains the final signer and approval gate. */
public final class AutoUpdateModule {
    public static final int API_MAJOR = 1;
    public static final long FOREGROUND_INTERVAL_MS = 10L * 60L * 1000L;
    public static final long BACKGROUND_INTERVAL_MS = 15L * 60L * 1000L;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final long MAX_APK_BYTES = 256L * 1024L * 1024L;
    private static final Object UPDATE_LOCK = new Object();
    private final Context context;
    private final SecureSettings settings;

    public AutoUpdateModule(Context context,SecureSettings settings){this.context=context.getApplicationContext();this.settings=settings;}

    public Result checkAndDownload(){
        synchronized(UPDATE_LOCK){return checkAndDownloadLocked();}
    }

    private Result checkAndDownloadLocked(){
        String manifestUrl=settings.updateManifestUrl().trim();
        if(manifestUrl.isEmpty())return new Result("not-configured","앱별 업데이트 manifest 주소가 설정되지 않았습니다.",0L);
        File temporary=null;
        try{
            validateUrl(manifestUrl,isDebuggable());
            JSONObject manifest=new JSONObject(readUrl(manifestUrl,MAX_MANIFEST_BYTES));
            long schema=manifest.getLong("schema"),version=manifest.getLong("version_code");
            String appId=manifest.getString("app_id"),packageName=manifest.getString("package_name");
            String versionName=manifest.getString("version"),apkUrl=manifest.getString("apk_url"),sha=manifest.getString("sha256").toLowerCase(Locale.ROOT);
            if(schema!=1||version<=0||versionName.trim().isEmpty()||!"android".equals(manifest.optString("platform"))||!AppIdentity.APP_ID.equals(appId)||!context.getPackageName().equals(packageName))return new Result("blocked","업데이트 앱 식별자가 일치하지 않습니다.",0L);
            if(version<=installedVersionCode())return new Result("up-to-date","최신 버전입니다.",version,versionName);
            if(!sha.matches("[0-9a-f]{64}"))return new Result("blocked","업데이트 SHA-256 형식이 올바르지 않습니다.",version);
            validateUrl(apkUrl,isDebuggable());
            File directory=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID);
            if(!directory.exists()&&!directory.mkdirs())return new Result("error","업데이트 임시 폴더를 만들지 못했습니다.",version);
            File target=new File(directory,"update-"+version+".apk");temporary=new File(directory,"update-"+version+".part");
            if(temporary.exists()&&!temporary.delete())return new Result("error","이전 다운로드 임시 파일을 정리하지 못했습니다.",version);
            download(apkUrl,temporary);
            if(temporary.length()<=0||temporary.length()>MAX_APK_BYTES)throw new IOException("Update package size is outside the allowed range");
            if(!sha256(temporary).equals(sha))throw new SecurityException("Update package SHA-256 does not match the manifest");
            verifyApk(temporary,version);
            replaceAtomically(temporary,target);temporary=null;
            settings.setPendingUpdate(target.getAbsolutePath(),version);
            return new Result("update-ready","업데이트 파일을 검증했습니다. 적용 버튼을 눌러 설치를 승인하세요.",version,versionName);
        }catch(Exception e){
            if(temporary!=null&&temporary.exists())temporary.delete();
            return new Result("error",safeMessage(e),0L);
        }
    }

    private static void replaceAtomically(File source,File target) throws IOException{
        try{Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }

    public Result installPending(Activity activity){
        synchronized(UPDATE_LOCK){return installPendingLocked(activity);}
    }

    public void clearPendingUpdateAfterSuccess(){
        synchronized(UPDATE_LOCK){
            String path=settings.pendingUpdatePath();
            if(!path.isEmpty()){
                try{File apk=new File(path).getCanonicalFile();File allowedRoot=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID).getCanonicalFile();
                    if(allowedRoot.equals(apk.getParentFile())&&apk.isFile())apk.delete();
                }catch(IOException ignored){}
            }
            settings.clearPendingUpdate();
        }
    }

    private Result installPendingLocked(Activity activity){
        String path=settings.pendingUpdatePath();
        if(path.isEmpty())return new Result("no-update","적용할 업데이트가 없습니다.",0L);
        try{
            File apk=new File(path).getCanonicalFile();
            File allowedRoot=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID).getCanonicalFile();
            if(!allowedRoot.equals(apk.getParentFile())||!apk.isFile()||apk.length()<=0||apk.length()>MAX_APK_BYTES)throw new IOException("Verified update package is unavailable");
            if(Build.VERSION.SDK_INT>=26&&!context.getPackageManager().canRequestPackageInstalls()){
                Intent permission=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName()));
                activity.startActivity(permission);
                return new Result("needs-permission","이 앱의 알 수 없는 앱 설치 권한을 허용한 뒤 다시 적용하세요.",settings.pendingUpdateVersionCode());
            }
            PackageInstaller installer=context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());params.setSize(apk.length());
            int sessionId=installer.createSession(params);
            try(PackageInstaller.Session session=installer.openSession(sessionId);FileInputStream input=new FileInputStream(apk);OutputStream output=session.openWrite("mobile-update.apk",0,apk.length())){
                byte[] buffer=new byte[64*1024];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);session.fsync(output);
                Intent resultIntent=new Intent(context,UpdateStatusActivity.class).setAction(UpdateStatusActivity.ACTION_INSTALL_RESULT).putExtra(PackageInstaller.EXTRA_SESSION_ID,sessionId);
                int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
                PendingIntent callback=PendingIntent.getActivity(context,sessionId,resultIntent,flags);
                session.commit(callback.getIntentSender());
            }catch(Exception e){installer.abandonSession(sessionId);throw e;}
            return new Result("install-started","Android 설치 화면에서 업데이트를 확인하세요.",settings.pendingUpdateVersionCode());
        }catch(Exception e){return new Result("error",safeMessage(e),settings.pendingUpdateVersionCode());}
    }

    public static boolean isSecureUrl(String value,boolean debugBuild){
        try{validateUrl(value,debugBuild);return true;}catch(RuntimeException e){return false;}
    }

    private long installedVersionCode() throws PackageManager.NameNotFoundException{
        PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
    }

    private void verifyApk(File file,long expectedVersion) throws Exception{
        PackageManager pm=context.getPackageManager();
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags);
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags);
        if(candidate==null||!context.getPackageName().equals(candidate.packageName))throw new SecurityException("APK package name does not match this app");
        long candidateVersion=Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
        if(candidateVersion!=expectedVersion||candidateVersion<=installedVersionCode())throw new SecurityException("APK version code is invalid");
        if(Build.VERSION.SDK_INT>=28){
            if(candidate.signingInfo==null||installed.signingInfo==null||!sameSigners(candidate.signingInfo.getApkContentsSigners(),installed.signingInfo.getApkContentsSigners()))throw new SecurityException("APK signing certificate does not match the installed app");
        }else if(candidate.signatures==null||installed.signatures==null||!sameSigners(candidate.signatures,installed.signatures))throw new SecurityException("APK signing certificate does not match the installed app");
    }

    private static boolean sameSigners(android.content.pm.Signature[] a,android.content.pm.Signature[] b){
        if(a.length==0||a.length!=b.length)return false;
        outer:for(android.content.pm.Signature left:a){for(android.content.pm.Signature right:b)if(MessageDigest.isEqual(left.toByteArray(),right.toByteArray()))continue outer;return false;}return true;
    }

    private String readUrl(String url,int maximum) throws IOException{
        HttpURLConnection connection=openFollowingRedirects(url,"application/json");
        try{int code=connection.getResponseCode();if(code!=200)throw new IOException("Manifest server returned HTTP "+code);return readLimited(connection.getInputStream(),maximum);}finally{connection.disconnect();}
    }

    private void download(String url,File target) throws IOException{
        HttpURLConnection connection=openFollowingRedirects(url,"application/vnd.android.package-archive, application/octet-stream");
        try{int code=connection.getResponseCode();if(code!=200)throw new IOException("Update server returned HTTP "+code);long declared=connection.getContentLengthLong();if(declared>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");try(InputStream in=connection.getInputStream();OutputStream out=Files.newOutputStream(target.toPath())){byte[] buffer=new byte[64*1024];int n;long total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");out.write(buffer,0,n);}}}finally{connection.disconnect();}
    }

    private HttpURLConnection openFollowingRedirects(String value,String accept) throws IOException{
        String current=value;Set<String> visited=new HashSet<>();
        for(int redirects=0;redirects<=5;redirects++){
            validateUrl(current,isDebuggable());
            if(!visited.add(current))throw new IOException("Update URL redirect loop detected");
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)new URL(current).openConnection();
                connection.setRequestMethod("GET");connection.setRequestProperty("Accept",accept);
                connection.setConnectTimeout(6000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);
                int code=connection.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String location=connection.getHeaderField("Location");
                    if(location==null||location.trim().isEmpty())throw new IOException("Update redirect has no destination");
                    if(redirects==5)throw new IOException("Update URL redirected too many times");
                    URI source=URI.create(current),destination=source.resolve(location).normalize();
                    String next=destination.toASCIIString();
                    if("https".equalsIgnoreCase(source.getScheme())&& !"https".equalsIgnoreCase(destination.getScheme()))
                        throw new IOException("Update redirect attempted to leave HTTPS");
                    if(!isAllowedRedirect(current,next,isDebuggable()))throw new IOException("Update redirect destination is not allowed");
                    connection.disconnect();connection=null;current=next;continue;
                }
                return connection;
            }catch(IOException|RuntimeException e){
                if(connection!=null)connection.disconnect();
                if(e instanceof IOException)throw(IOException)e;
                throw new IOException("Update redirect URL is invalid",e);
            }
        }
        throw new IOException("Update URL redirected too many times");
    }

    public static boolean isAllowedRedirect(String from,String to,boolean debugBuild){
        try{
            validateUrl(from,debugBuild);validateUrl(to,debugBuild);
            URI source=URI.create(from),destination=URI.create(to);
            return !"https".equalsIgnoreCase(source.getScheme())||"https".equalsIgnoreCase(destination.getScheme());
        }catch(RuntimeException e){return false;}
    }

    private boolean isDebuggable(){return(context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;}

    private static void validateUrl(String value,boolean debugBuild){
        URI uri=URI.create(value);String scheme=uri.getScheme(),host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Update URLs must not embed credentials or a fragment.");
        if("https".equalsIgnoreCase(scheme))return;
        boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)||(debugBuild&&"10.0.2.2".equals(host));
        if(!"http".equalsIgnoreCase(scheme)||!local)throw new IllegalArgumentException("Updates require trusted HTTPS outside local debug tests.");
    }

    private static String readLimited(InputStream input,int maximum) throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>maximum)throw new IOException("Update manifest is too large");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}}
    private static String sha256(File file) throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte x:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",x&0xff));return hex.toString();}
    public static String safeMessage(Exception e){
        String detail=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.ROOT);
        if(detail.contains("sha-256"))return "내려받은 APK의 SHA-256이 업데이트 manifest와 다릅니다.";
        if(detail.contains("package name"))return "APK 패키지명이 현재 설치된 앱과 다릅니다.";
        if(detail.contains("version code"))return "APK versionCode가 업데이트 manifest와 다릅니다.";
        if(detail.contains("signing certificate"))return "APK 서명 인증서가 현재 설치된 앱과 다릅니다.";
        if(detail.contains("manifest server returned http 404"))return "GitHub에서 업데이트 manifest를 찾지 못했습니다 (HTTP 404).";
        if(detail.contains("update server returned http 404"))return "GitHub 릴리스에서 APK 파일을 찾지 못했습니다 (HTTP 404).";
        if(detail.contains("redirect"))return "업데이트 주소가 안전하지 않은 리디렉션을 반환했습니다.";
        if(e instanceof SecurityException)return "업데이트 파일 무결성 검증에 실패했습니다.";
        if(e instanceof IOException)return "업데이트 서버 접속 또는 파일 다운로드에 실패했습니다.";
        return "업데이트 manifest 주소나 형식을 확인하세요.";
    }

    public static final class Result{public final String status,message,versionName;public final long versionCode;Result(String s,String m,long v){this(s,m,v,"");}Result(String s,String m,long v,String name){status=s;message=m;versionCode=v;versionName=name==null?"":name;}}
}
