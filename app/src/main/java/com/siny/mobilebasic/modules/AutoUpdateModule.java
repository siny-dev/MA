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
import com.siny.mobilebasic.BuildConfig;

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
    enum UpdateSource { LOCAL_MANIFEST, GITHUB_RELEASE, NONE }
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
        String repository=effectiveRepository(settings.githubUpdateRepository(),BuildConfig.GITHUB_UPDATE_REPOSITORY);
        UpdateSource source=chooseSource(manifestUrl,repository);
        if(source==UpdateSource.GITHUB_RELEASE)return checkGithubAndDownload(repository);
        if(source==UpdateSource.NONE)return new Result("not-configured","앱별 업데이트 manifest 주소가 설정되지 않았습니다.",0L);
        File temporary=null;
        long version=0L;
        String versionName="";
        try{
            validateUrl(manifestUrl,isDebuggable());
            JSONObject manifest=new JSONObject(readUrl(manifestUrl,MAX_MANIFEST_BYTES));
            long schema=manifest.getLong("schema");version=manifest.getLong("version_code");
            String appId=manifest.getString("app_id"),packageName=manifest.getString("package_name");
            versionName=manifest.getString("version");
            String apkUrl=manifest.getString("apk_url"),sha=manifest.getString("sha256").toLowerCase(Locale.ROOT);
            if(schema!=1||version<=0||!"android".equals(manifest.optString("platform"))||!AppIdentity.APP_ID.equals(appId)||!context.getPackageName().equals(packageName))return new Result("blocked","업데이트 앱 식별자가 일치하지 않습니다.",0L);
            if(version<=installedVersionCode())return new Result("up-to-date",versionStatusMessage(versionName,version),version);
            if(!sha.matches("[0-9a-f]{64}"))return new Result("blocked","업데이트 SHA-256 형식이 올바르지 않습니다.",version);
            validateUrl(apkUrl,isDebuggable());
            File directory=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID);
            if(!directory.exists()&&!directory.mkdirs())return new Result("error","업데이트 임시 폴더를 만들지 못했습니다.",version);
            File target=new File(directory,"update-"+version+".apk");temporary=new File(directory,"update-"+version+".part");
            if(temporary.exists()&&!temporary.delete())return new Result("error","이전 다운로드 임시 파일을 정리하지 못했습니다.",version);
            download(apkUrl,temporary);
            if(temporary.length()<=0||temporary.length()>MAX_APK_BYTES)throw new IOException("Update package size is outside the allowed range");
            if(!sha256(temporary).equals(sha))throw new UpdateVerificationException("다운로드한 APK의 SHA-256이 배포 manifest와 일치하지 않아 설치를 중단했습니다.");
            verifyApk(temporary,version);
            replaceAtomically(temporary,target);temporary=null;
            settings.setPendingUpdate(target.getAbsolutePath(),version);
            return new Result("update-ready","새 버전 "+versionName+" ("+version+")을 내려받았습니다. Android 설치 확인을 이어갑니다.",version);
        }catch(Exception e){
            if(temporary!=null&&temporary.exists())temporary.delete();
            return new Result("error",versionedFailure(safeMessage(e),versionName,version),version);
        }
    }

    static UpdateSource chooseSource(String manifestUrl,String repository){
        if(repository!=null&&!repository.trim().isEmpty())return UpdateSource.GITHUB_RELEASE;
        if(manifestUrl!=null&&!manifestUrl.trim().isEmpty())return UpdateSource.LOCAL_MANIFEST;
        return UpdateSource.NONE;
    }

    static String effectiveRepository(String savedRepository,String buildRepository){
        if(buildRepository!=null&&!buildRepository.trim().isEmpty())return buildRepository.trim();
        return savedRepository==null?"":savedRepository.trim();
    }

    static String effectiveGithubToken(boolean publicRepository,String savedToken){
        return publicRepository||savedToken==null?"":savedToken.trim();
    }

    static boolean needsGithubToken(boolean publicRepository,String token,String message){
        if(publicRepository)return false;
        String detail=message==null?"":message;
        return token==null||token.trim().isEmpty()||detail.contains("토큰")
                ||detail.contains("저장소에 접근할 수 없습니다");
    }

    static String githubAccessFailureMessage(int code,boolean tokenPresent,boolean assetRequest){
        if(code==401)return tokenPresent?"GitHub 읽기 토큰이 올바르지 않습니다."
                :"GitHub 공개 업데이트 요청이 인증 거부되었습니다. (HTTP 401)";
        if(code==403)return tokenPresent?"GitHub 읽기 토큰에 저장소 읽기 권한이 없습니다."
                :"GitHub 공개 업데이트 요청이 제한되었거나 호출 한도에 도달했습니다. (HTTP 403)";
        if(code==404)return assetRequest?"GitHub 공개 업데이트 파일을 찾을 수 없습니다. (HTTP 404)"
                :"GitHub 공개 저장소 또는 업데이트 목록을 찾을 수 없습니다. (HTTP 404)";
        return "GitHub 업데이트 요청이 실패했습니다. (HTTP "+code+")";
    }

    private String versionStatusMessage(String availableVersionName,long availableVersionCode)
            throws PackageManager.NameNotFoundException{
        long installed=installedVersionCode();
        String installedName=com.siny.mobilebasic.BuildConfig.VERSION_NAME;
        if(availableVersionCode==installed)
            return "설치 버전 "+installedName+" ("+installed+") · 최신 배포 "+availableVersionName+" ("+availableVersionCode+")";
        return "설치 버전 "+installedName+" ("+installed+") · 확인한 배포 "+availableVersionName+" ("+availableVersionCode+") · 설치된 앱이 더 최신입니다.";
    }

    private Result checkGithubAndDownload(String repository){
        String token=effectiveGithubToken(BuildConfig.GITHUB_UPDATE_REPOSITORY_PUBLIC,
                settings.githubUpdateToken());
        File temporary=null;
        long version=0L;
        String versionName="";
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
            long schema=manifest.getLong("schema");version=manifest.getLong("version_code");
            String appId=manifest.getString("app_id"),packageName=manifest.getString("package_name");
            String platform=manifest.getString("platform");versionName=manifest.getString("version");
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
            if(version<=installedVersionCode())return new Result("up-to-date",versionStatusMessage(versionName,version),version);

            File directory=new File(context.getNoBackupFilesDir(),"autoupdate/"+AppIdentity.APP_ID);
            if(!directory.exists()&&!directory.mkdirs())return new Result("error","업데이트 임시 폴더를 만들지 못했습니다.",version);
            temporary=new File(directory,"update-"+version+".part");
            if(temporary.exists()&&!temporary.delete())return new Result("error","이전 다운로드 임시 파일을 정리하지 못했습니다.",version);
            downloadGithubAssetWithFallback(repository,token,candidate.tag,apkName,apkAssetId,
                    temporary,declaredSize,sha);
            verifyApk(temporary,version);
            File target=new File(directory,"update-"+version+".apk");
            replaceAtomically(temporary,target);temporary=null;
            settings.setPendingUpdate(target.getAbsolutePath(),version);
            return new Result("update-ready","새 버전 "+versionName+" ("+version+")을 내려받았습니다. Android 설치 확인을 이어갑니다.",version);
        }catch(Exception e){
            if(temporary!=null&&temporary.exists())temporary.delete();
            if(e instanceof GithubAccessException&&BuildConfig.GITHUB_UPDATE_REPOSITORY_PUBLIC){
                return new Result("error","MA 공개 업데이트 저장소 연결 실패 · "+e.getMessage(),version);
            }
            if(e instanceof GithubAccessException&&needsGithubToken(
                    BuildConfig.GITHUB_UPDATE_REPOSITORY_PUBLIC,token,e.getMessage())){
                return new Result("needs-github-token",
                        "업데이트 저장소 연결을 한 번 설정해 주세요.",0L);
            }
            return new Result("error",versionedFailure(safeMessage(e),versionName,version),version);
        }
    }

    private String readGithubApi(String url,String token,int maximum) throws IOException{
        HttpURLConnection connection=githubApi(url,token);
        try{
            int code=connection.getResponseCode();
            if(code==401||code==403||code==404)return throwGithub(
                    githubAccessFailureMessage(code,token!=null&&!token.isEmpty(),false));
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

    /**
     * GitHub's asset API sometimes returns a damaged/short response on mobile networks.
     * Retry once through the public release URL, then accept bytes only after both the
     * manifest size and SHA-256 match. The fallback never weakens package/signature checks.
     */
    private void downloadGithubAssetWithFallback(String repository,String token,String tag,
            String assetName,long assetId,File target,long expectedSize,String expectedSha)
            throws IOException{
        IOException lastFailure=null;
        for(int attempt=0;attempt<2;attempt++){
            try{
                Files.deleteIfExists(target.toPath());
                if(attempt==0)downloadGithubAsset(repository,token,assetId,target);
                else downloadGithubReleaseAsset(repository,tag,assetName,target);
                if(target.length()!=expectedSize||target.length()<=0||target.length()>MAX_APK_BYTES)
                    throw new IOException("Downloaded APK size does not match the release manifest");
                if(!sha256(target).equals(expectedSha))
                    throw new IOException("Downloaded APK SHA-256 does not match the release manifest");
                return;
            }catch(Exception e){
                if(target.exists()&&!target.delete())e.addSuppressed(
                        new IOException("Unable to remove invalid temporary APK"));
                lastFailure=e instanceof IOException?(IOException)e:new IOException(e);
            }
        }
        if(lastFailure!=null&&lastFailure.getMessage()!=null
                &&(lastFailure.getMessage().contains("SHA-256")
                ||lastFailure.getMessage().contains("size does not match"))){
            throw new UpdateVerificationException(
                    "GitHub API와 공개 릴리스 주소에서 다시 받은 APK가 모두 배포 manifest의 크기 또는 SHA-256과 맞지 않습니다.");
        }
        throw new IOException("GitHub APK 다운로드를 두 경로에서 완료하지 못했습니다.",lastFailure);
    }

    private void downloadGithubReleaseAsset(String repository,String tag,String assetName,File target)
            throws IOException{
        String url=githubReleaseAssetUrl(repository,tag,assetName,AppIdentity.APP_ID);
        HttpURLConnection connection=openGithubReleaseAsset(url);
        try{
            long declared=connection.getContentLengthLong();
            if(declared>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");
            try(InputStream in=connection.getInputStream();OutputStream out=Files.newOutputStream(target.toPath())){
                byte[] buffer=new byte[64*1024];int n;long total=0;
                while((n=in.read(buffer))!=-1){
                    total+=n;
                    if(total>MAX_APK_BYTES)throw new IOException("Update package exceeds size limit");
                    out.write(buffer,0,n);
                }
            }
        }finally{connection.disconnect();}
    }

    static String githubReleaseAssetUrl(String repository,String tag,String assetName,String appId){
        if(repository==null||!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                ||repository.startsWith(".")||repository.endsWith("/.")||repository.endsWith("/..")
                ||repository.contains("/../")||repository.contains("/./"))
            throw new IllegalArgumentException("Invalid GitHub repository");
        if(tag==null||!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Invalid GitHub release tag");
        if(appId==null||!appId.matches("[A-Za-z0-9_-]+")||assetName==null
                ||!assetName.equals("MA-"+appId+"-"+tag.substring(1)+".apk"))
            throw new IllegalArgumentException("Invalid GitHub APK asset name");
        return "https://github.com/"+repository+"/releases/download/"+tag+"/"+assetName;
    }

    private HttpURLConnection openGithubReleaseAsset(String url) throws IOException{
        URI uri=URI.create(url);
        if(!"https".equalsIgnoreCase(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())
                ||uri.getUserInfo()!=null||uri.getFragment()!=null)
            throw new IOException("GitHub release download URL is not trusted");
        HttpURLConnection connection=open(url);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept","application/octet-stream");
        connection.setRequestProperty("User-Agent","MA-AutoUpdate/1");
        int code=connection.getResponseCode();
        if(code==200)return connection;
        if(code!=301&&code!=302&&code!=303&&code!=307&&code!=308){
            connection.disconnect();
            throw new IOException("GitHub release APK returned HTTP "+code);
        }
        String location=connection.getHeaderField("Location");
        connection.disconnect();
        if(location==null||!isTrustedGithubAssetUrl(location))
            throw new IOException("GitHub release APK redirect is not trusted");
        HttpURLConnection asset=(HttpURLConnection)URI.create(location).toURL().openConnection();
        asset.setConnectTimeout(6000);asset.setReadTimeout(20000);asset.setUseCaches(false);
        asset.setInstanceFollowRedirects(false);asset.setRequestMethod("GET");
        asset.setRequestProperty("Accept","application/octet-stream");
        asset.setRequestProperty("Accept-Encoding","identity");
        asset.setRequestProperty("Cache-Control","no-cache");
        int assetCode=asset.getResponseCode();
        if(assetCode!=200){asset.disconnect();throw new IOException("GitHub release asset returned HTTP "+assetCode);}
        return asset;
    }

    private HttpURLConnection githubAssetResponse(String repository,String token,long assetId) throws IOException{
        if(assetId<=0)throw new IOException("GitHub update asset is invalid");
        HttpURLConnection api=githubApi("https://api.github.com/repos/"+repository+
                "/releases/assets/"+assetId,token);
        api.setRequestProperty("Accept","application/octet-stream");
        int code=api.getResponseCode();
        if(code==200)return api;
        if(code==401||code==403||code==404){api.disconnect();throw new GithubAccessException(
                githubAccessFailureMessage(code,token!=null&&!token.isEmpty(),true));}
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

    static boolean isTrustedGithubAssetUrl(String value){
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
        if(installed==null)throw new UpdateVerificationException("현재 설치된 MA의 패키지 정보를 읽지 못했습니다.");
        if(Build.VERSION.SDK_INT>=28){
            // Some PackageManager implementations return PackageInfo but omit signingInfo
            // for archives. Retry with the deprecated flag only as a data-source fallback;
            // the same exact signer comparison remains mandatory.
            if(candidate==null||candidate.signingInfo==null
                    ||candidate.signingInfo.getApkContentsSigners()==null
                    ||candidate.signingInfo.getApkContentsSigners().length==0){
                PackageInfo legacyCandidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);
                if(legacyCandidate!=null&&candidate==null)candidate=legacyCandidate;
                else if(legacyCandidate!=null)candidate.signatures=legacyCandidate.signatures;
            }
            if(installed.signingInfo==null||installed.signingInfo.getApkContentsSigners()==null
                    ||installed.signingInfo.getApkContentsSigners().length==0){
                PackageInfo legacyInstalled=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
                if(legacyInstalled!=null)installed.signatures=legacyInstalled.signatures;
            }
        }
        if(candidate==null)throw new UpdateVerificationException("Android가 다운로드한 APK의 패키지 정보를 읽지 못했습니다.");
        if(!context.getPackageName().equals(candidate.packageName))throw new UpdateVerificationException("다운로드한 APK가 이 MA 앱의 패키지가 아닙니다.");
        long candidateVersion=Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
        if(candidateVersion!=expectedVersion||candidateVersion<=installedVersionCode())throw new UpdateVerificationException("다운로드한 APK의 버전 코드가 배포 정보와 다르거나 설치본보다 낮습니다.");
        if(Build.VERSION.SDK_INT>=28){
            android.content.pm.Signature[] candidateSigners=candidate.signingInfo==null
                    ?candidate.signatures:candidate.signingInfo.getApkContentsSigners();
            android.content.pm.Signature[] installedSigners=installed.signingInfo==null
                    ?installed.signatures:installed.signingInfo.getApkContentsSigners();
            if(!sameSigners(candidateSigners,installedSigners))throw new UpdateVerificationException("다운로드한 APK의 서명이 현재 설치된 MA와 다릅니다.");
        }else if(candidate.signatures==null||installed.signatures==null||!sameSigners(candidate.signatures,installed.signatures))throw new UpdateVerificationException("다운로드한 APK의 서명이 현재 설치된 MA와 다릅니다.");
    }

    private static boolean sameSigners(android.content.pm.Signature[] a,android.content.pm.Signature[] b){
        if(a==null||b==null||a.length==0||a.length!=b.length)return false;
        byte[][] left=new byte[a.length][];byte[][] right=new byte[b.length][];
        for(int i=0;i<a.length;i++)left[i]=a[i].toByteArray();
        for(int i=0;i<b.length;i++)right[i]=b[i].toByteArray();
        return sameSignerBytes(left,right);
    }

    static boolean sameSignerBytes(byte[][] a,byte[][] b){
        if(a==null||b==null||a.length==0||a.length!=b.length)return false;
        outer:for(byte[] left:a){
            if(left==null)return false;
            for(byte[] right:b){
                if(right!=null&&MessageDigest.isEqual(left,right))continue outer;
            }
            return false;
        }
        return true;
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
        HttpURLConnection connection=(HttpURLConnection)URI.create(value).toURL().openConnection();
        connection.setConnectTimeout(6000);connection.setReadTimeout(12000);
        connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Encoding","identity");
        connection.setRequestProperty("Cache-Control","no-cache");
        return connection;
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
        if(e instanceof UpdateVerificationException)return e.getMessage();
        if(e instanceof SecurityException)return "Android가 업데이트 파일 접근을 보안상 거부했습니다.";
        if(e instanceof GithubAccessException)return e.getMessage();
        if(e instanceof IOException)return "업데이트 주소에 연결할 수 없거나 파일을 내려받지 못했습니다.";
        return "업데이트 주소나 manifest 형식을 확인하세요.";
    }

    static String versionedFailure(String message,String versionName,long versionCode){
        String installed="설치 버전 "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")";
        if(versionCode<=0)return installed+" · "+message;
        String label=versionName==null||versionName.trim().isEmpty()?Long.toString(versionCode):versionName+" ("+versionCode+")";
        return installed+" → 확인한 업데이트 "+label+" · "+message;
    }

    private static final class UpdateVerificationException extends SecurityException{
        UpdateVerificationException(String message){super(message);}
    }

    public static final class Result{public final String status,message;public final long versionCode;Result(String s,String m,long v){status=s;message=m;versionCode=v;}}
}
