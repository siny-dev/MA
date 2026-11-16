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
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

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
        String repository=settings.githubUpdateRepository().trim();
        if(!repository.isEmpty())return checkGithubAndDownload(repository);
        String manifestUrl=settings.updateManifestUrl().trim();
        if(manifestUrl.isEmpty())return new Result("not-configured","앱별 업데이트 manifest 주소가 설정되지 않았습니다.",0L);
        File temporary=null;
        try{
            validateUrl(manifestUrl,isDebuggable());
            JSONObject manifest=new JSONObject(readUrl(manifestUrl,MAX_MANIFEST_BYTES));
            long schema=manifest.getLong("schema"),version=manifest.getLong("version_code");
            String appId=manifest.getString("app_id"),packageName=manifest.getString("package_name");
            String apkUrl=manifest.getString("apk_url"),sha=manifest.getString("sha256").toLowerCase(Locale.ROOT);
            if(schema!=1||version<=0||!"android".equals(manifest.optString("platform"))||!AppIdentity.APP_ID.equals(appId)||!context.getPackageName().equals(packageName))return new Result("blocked","업데이트 앱 식별자가 일치하지 않습니다.",0L);
            if(version<=installedVersionCode())return new Result("up-to-date","최신 버전입니다.",version);
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
            return new Result("update-ready","업데이트 파일을 확인했습니다. Android 설치 확인을 이어갑니다.",version);
        }catch(Exception e){
            if(temporary!=null&&temporary.exists())temporary.delete();
            return new Result("error",safeMessage(e),0L);
        }
    }

    private Result checkGithubAndDownload(String repository){
        String token=settings.githubUpdateToken().trim();
        File temporary=null;
        try{
            if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                    ||repository.startsWith(".")||repository.endsWith("/.")||repository.endsWith("/..")
                    ||repository.contains("/../")||repository.contains("/./"))
                throw new IllegalArgumentException("GitHub 저장소는 소유자/저장소 형식이어야 합니다.");
            if(!token.isEmpty()&&(token.length()>512||!token.matches("[A-Za-z0-9_]+")))
                throw new IllegalArgumentException("GitHub 읽기 토큰 형식을 확인하세요.");
            String releasesUrl="https://api.github.com/repos/"+repository+"/releases?per_page=100";
            JSONArray releases=new JSONArray(readGithubApi(releasesUrl,token,4*1024*1024));
            GitHubReleaseResolver.Candidate candidate=GitHubReleaseResolver.newestForApp(
                    releases.toString(),AppIdentity.APP_ID);
            if(candidate==null)return new Result("no-release","이 MA 앱의 GitHub 업데이트가 아직 없습니다.",0L);

            String manifestName="MA-"+AppIdentity.APP_ID+"-manifest.json";
            JSONObject manifest=new JSONObject(new String(readGithubAsset(repository,token,
                    candidate.manifestAssetId,MAX_MANIFEST_BYTES),StandardCharsets.UTF_8));
            long schema=manifest.getLong("schema"),version=manifest.getLong("version_code");
            String appId=manifest.getString("app_id"),packageName=manifest.getString("package_name");
            String platform=manifest.getString("platform"),versionName=manifest.getString("version");
            String apkName=manifest.getString("apk_asset_name"),sha=manifest.getString("sha256").toLowerCase(Locale.ROOT);
            long declaredSize=manifest.getLong("size");
            String expectedTag="v"+versionName;
            if(schema!=1||!"android".equals(platform)||!AppIdentity.APP_ID.equals(appId)
                    ||!context.getPackageName().equals(packageName)||!expectedTag.equals(candidate.tag)
                    ||!apkName.matches("MA-"+Pattern.quote(AppIdentity.APP_ID)+"-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")
                    ||!sha.matches("[0-9a-f]{64}")||version<=0||declaredSize<=0||declaredSize>MAX_APK_BYTES)
                return new Result("blocked","GitHub 업데이트의 앱 식별 정보가 맞지 않습니다.",0L);
            long apkAssetId=candidate.assetId(apkName),assetSize=candidate.assetSize(apkName);
            if(apkAssetId<=0||assetSize!=declaredSize)
                return new Result("blocked","GitHub 업데이트 APK 파일 정보가 manifest와 맞지 않습니다.",version);
            if(version<=installedVersionCode())return new Result("up-to-date","최신 버전입니다.",version);

            File directory=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID);
            if(!directory.exists()&&!directory.mkdirs())return new Result("error","업데이트 임시 폴더를 만들지 못했습니다.",version);
            temporary=new File(directory,"update-"+version+".part");
            if(temporary.exists()&&!temporary.delete())return new Result("error","이전 다운로드 임시 파일을 정리하지 못했습니다.",version);
            downloadGithubAsset(repository,token,apkAssetId,temporary);
            if(temporary.length()!=declaredSize||temporary.length()<=0||temporary.length()>MAX_APK_BYTES)
                throw new IOException("GitHub update size does not match the release manifest");
            if(!sha256(temporary).equals(sha))throw new SecurityException("Update package SHA-256 does not match the manifest");
            verifyApk(temporary,version);
            File target=new File(directory,"update-"+version+".apk");
            replaceAtomically(temporary,target);temporary=null;
            settings.setPendingUpdate(target.getAbsolutePath(),version);
            return new Result("update-ready","GitHub 업데이트 파일을 확인했습니다. Android 설치 확인을 이어갑니다.",version);
        }catch(Exception e){
            if(temporary!=null&&temporary.exists())temporary.delete();
            if(e instanceof GithubAccessException
                    &&(token.isEmpty()||e.getMessage().contains("토큰")
                    ||e.getMessage().contains("저장소에 접근할 수 없습니다"))){
                return new Result("needs-github-token",
                        "업데이트 저장소 연결을 한 번 설정해 주세요.",0L);
            }
            return new Result("error",safeMessage(e),0L);
        }
    }

    private String readGithubApi(String url,String token,int maximum) throws IOException{
        HttpURLConnection connection=githubApi(url,token);
        try{
            int code=connection.getResponseCode();
            if(code==401)return throwGithub("GitHub 읽기 토큰이 올바르지 않습니다.");
            if(code==403)return throwGithub("GitHub 읽기 토큰에 저장소 읽기 권한이 없습니다.");
            if(code==404)return throwGithub("GitHub 저장소에 접근할 수 없습니다.");
            if(code!=200)return throwGithub("GitHub가 업데이트 목록을 반환하지 않았습니다. (HTTP "+code+")");
            return readLimited(connection.getInputStream(),maximum);
        }finally{connection.disconnect();}
    }

    private byte[] readGithubAsset(String repository,String token,long assetId,int maximum) throws IOException{
        HttpURLConnection connection=githubAssetResponse(repository,token,assetId);
        try{return readLimitedBytes(connection.getInputStream(),maximum);}finally{connection.disconnect();}
    }

    private void downloadGithubAsset(String repository,String token,long assetId,File target) throws IOException{
        HttpURLConnection connection=githubAssetResponse(repository,token,assetId);
        try{
            long declared=connection.getContentLengthLong();
            if(declared>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");
            try(InputStream in=connection.getInputStream();OutputStream out=Files.newOutputStream(target.toPath())){
                byte[] buffer=new byte[64*1024];int n;long total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");out.write(buffer,0,n);}
            }
        }finally{connection.disconnect();}
    }

    private HttpURLConnection githubAssetResponse(String repository,String token,long assetId) throws IOException{
        if(assetId<=0)throw new IOException("GitHub update asset is invalid");
        HttpURLConnection api=githubApi("https://api.github.com/repos/"+repository+
                "/releases/assets/"+assetId,token);
        api.setRequestProperty("Accept","application/octet-stream");
        int code=api.getResponseCode();
        if(code==200)return api;
        if(code==401){api.disconnect();throw new GithubAccessException("GitHub 읽기 토큰이 올바르지 않습니다.");}
        if(code==403){api.disconnect();throw new GithubAccessException("GitHub 읽기 토큰에 저장소 읽기 권한이 없습니다.");}
        if(code==404){api.disconnect();throw new GithubAccessException("GitHub 저장소 업데이트 파일에 접근할 수 없습니다.");}
        if(code!=301&&code!=302&&code!=303&&code!=307&&code!=308){api.disconnect();throw new GithubAccessException("GitHub 업데이트 파일을 열지 못했습니다. (HTTP "+code+")");}
        String location=api.getHeaderField("Location");api.disconnect();
        if(location==null||!isTrustedGithubAssetUrl(location))throw new GithubAccessException("GitHub 업데이트 파일 주소를 확인할 수 없습니다.");
        HttpURLConnection asset=(HttpURLConnection)URI.create(location).toURL().openConnection();
        asset.setConnectTimeout(6000);asset.setReadTimeout(20000);asset.setInstanceFollowRedirects(false);
        asset.setRequestMethod("GET");asset.setRequestProperty("Accept","application/octet-stream");
        int assetCode=asset.getResponseCode();
        if(assetCode!=200){asset.disconnect();throw new GithubAccessException("GitHub 업데이트 파일을 내려받지 못했습니다. (HTTP "+assetCode+")");}
        return asset;
    }

    private HttpURLConnection githubApi(String url,String token) throws IOException{
        URI uri=URI.create(url);
        if(!"https".equalsIgnoreCase(uri.getScheme())||!"api.github.com".equalsIgnoreCase(uri.getHost()))
            throw new IOException("GitHub API URL is not trusted");
        HttpURLConnection connection=open(url);connection.setRequestMethod("GET");
        if(token!=null&&!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        connection.setRequestProperty("User-Agent","MA-AutoUpdate/1");
        return connection;
    }

    private static boolean isTrustedGithubAssetUrl(String value){
        try{
            URI uri=URI.create(value);String host=uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())&&uri.getUserInfo()==null&&uri.getFragment()==null
                    &&host!=null&&(host.toLowerCase(Locale.ROOT).endsWith(".githubusercontent.com")
                    ||host.toLowerCase(Locale.ROOT).matches("github-production-release-asset-[a-z0-9-]+\\.s3\\.amazonaws\\.com"));
        }catch(RuntimeException e){return false;}
    }

    private static String throwGithub(String message) throws GithubAccessException{throw new GithubAccessException(message);}

    private static byte[] readLimitedBytes(InputStream input,int maximum) throws IOException{
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=in.read(buffer))!=-1){total+=n;if(total>maximum)throw new IOException("GitHub update asset is too large");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }

    private static final class GithubAccessException extends IOException{
        GithubAccessException(String message){super(message);}
    }

    private static void replaceAtomically(File source,File target) throws IOException{
        try{Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }

    public Result installPending(Activity activity){
        synchronized(UPDATE_LOCK){return installPendingLocked(activity);}
    }

    public Result installPendingFromBackground(){
        synchronized(UPDATE_LOCK){return installPendingLocked(null);}
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
                if(activity!=null){
                    Intent permission=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName()));
                    activity.startActivity(permission);
                }
                return new Result("needs-permission","Android 설정에서 이 앱의 설치 권한을 허용해 주세요.",settings.pendingUpdateVersionCode());
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
        HttpURLConnection connection=open(url);connection.setRequestMethod("GET");connection.setRequestProperty("Accept","application/json");
        try{int code=connection.getResponseCode();if(code!=200)throw new IOException("Manifest server returned HTTP "+code);return readLimited(connection.getInputStream(),maximum);}finally{connection.disconnect();}
    }

    private void download(String url,File target) throws IOException{
        HttpURLConnection connection=open(url);connection.setRequestMethod("GET");connection.setRequestProperty("Accept","application/vnd.android.package-archive, application/octet-stream");
        try{int code=connection.getResponseCode();if(code!=200)throw new IOException("Update server returned HTTP "+code);long declared=connection.getContentLengthLong();if(declared>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");try(InputStream in=connection.getInputStream();OutputStream out=Files.newOutputStream(target.toPath())){byte[] buffer=new byte[64*1024];int n;long total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");out.write(buffer,0,n);}}}finally{connection.disconnect();}
    }

    private HttpURLConnection open(String value) throws IOException{
        HttpURLConnection connection=(HttpURLConnection)URI.create(value).toURL().openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);return connection;
    }

    private boolean isDebuggable(){return(context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;}

    private static void validateUrl(String value,boolean debugBuild){
        URI uri=URI.create(value);String scheme=uri.getScheme(),host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Update URLs must not embed credentials or a fragment.");
        if("https".equalsIgnoreCase(scheme))return;
        boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)
                ||(debugBuild&&("10.0.2.2".equals(host)||isPrivateIpv4(host)));
        if(!"http".equalsIgnoreCase(scheme)||!local)throw new IllegalArgumentException("Updates require trusted HTTPS outside local debug tests.");
    }

    private static boolean isPrivateIpv4(String host){
        String[] parts=host.split("\\.",-1);if(parts.length!=4)return false;
        int[] octets=new int[4];
        for(int i=0;i<4;i++){
            if(parts[i].isEmpty()||parts[i].length()>3)return false;
            try{octets[i]=Integer.parseInt(parts[i]);}catch(NumberFormatException e){return false;}
            if(octets[i]<0||octets[i]>255)return false;
        }
        return octets[0]==10
                ||(octets[0]==172&&octets[1]>=16&&octets[1]<=31)
                ||(octets[0]==192&&octets[1]==168)
                ||(octets[0]==169&&octets[1]==254);
    }

    private static String readLimited(InputStream input,int maximum) throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>maximum)throw new IOException("Update manifest is too large");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}}
    private static String sha256(File file) throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte x:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",x&0xff));return hex.toString();}
    private static String safeMessage(Exception e){
        if(e instanceof SecurityException)return "업데이트 파일의 SHA-256·서명·패키지 검증에 실패했습니다.";
        if(e instanceof GithubAccessException)return e.getMessage();
        if(e instanceof IOException)return "업데이트 주소에 연결할 수 없거나 파일을 내려받지 못했습니다.";
        return "업데이트 주소나 manifest 형식을 확인하세요.";
    }

    public static final class Result{public final String status,message;public final long versionCode;Result(String s,String m,long v){status=s;message=m;versionCode=v;}}
}
