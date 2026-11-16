package com.siny.mobilebasic.modules;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.siny.mobilebasic.AppIdentity;
import com.siny.mobilebasic.storage.MobileStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Android adapter for AutoSync protocol v1. Local edits stay queued until the server acknowledges them. */
public final class AutoSyncModule {
    public static final int API_MAJOR = 1;
    public static final long FOREGROUND_INTERVAL_MS = 10_000L;
    public static final long BACKGROUND_INTERVAL_MS = 15L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final Context context;
    private final MobileStore store;
    private final SecureSettings settings;
    private final Object runLock = new Object();

    public AutoSyncModule(Context context, MobileStore store, SecureSettings settings) {
        this.context=context.getApplicationContext(); this.store=store; this.settings=settings;
    }

    public Result syncOnce() {
        synchronized (runLock) {
            String endpoint=settings.serverUrl().trim(), token;
            try { token=settings.autosyncToken(); }
            catch (RuntimeException e) { return result("needs-credential", "인증 정보를 다시 입력하세요."); }
            if (endpoint.isEmpty() || token.isEmpty()) return result("needs-configuration", "서버 주소와 앱 인증 정보를 설정하세요.");
            try {
                validateEndpoint(endpoint, isDebuggable());
                pull(endpoint, token);
                JSONArray pending=store.claimPending();
                for(int i=0;i<pending.length();i++) {
                    JSONObject operation=pending.optJSONObject(i);
                    if(operation==null){releaseClaimedFrom(pending,i);return result("blocked","로컬 동기화 대기열 형식을 확인하세요.");}
                    try {
                        JSONObject body=new JSONObject(); body.put("payload",operation.getJSONObject("payload")); body.put("deleted",operation.getBoolean("deleted"));
                        String rid=operation.getString("record_id");
                        if(!rid.matches("[A-Za-z0-9._:-]{1,128}")) throw new JSONException("Invalid local record ID");
                        String url=base(endpoint)+"/v1/apps/"+encode(AppIdentity.APP_ID)+"/records/"+encode(rid);
                        Response response=request("POST",url,token,body.toString(),operation.getLong("expected_revision"),operation.getString("op_id"));
                        if(response.code==200) store.markSynced(operation,response.body);
                        else if(response.code==409) {
                            store.markConflict(operation,response.body);
                            releaseClaimedFrom(pending,i+1);
                            return result("conflict","충돌한 작업은 양쪽 내용을 보존했습니다. 선택해서 해결하세요.");
                        } else {
                            releaseClaimedFrom(pending,i);
                            if(response.code==401||response.code==403) return result("needs-credential", "서버 인증이 필요합니다.");
                            return result("offline", "서버가 변경을 수락하지 않아 로컬 대기열에 보관했습니다.");
                        }
                    } catch(UnauthorizedException e) {
                        releaseClaimedFrom(pending,i);
                        return result("needs-credential", "서버 인증이 필요합니다.");
                    } catch(IOException e) {
                        releaseClaimedFrom(pending,i);
                        return result("offline", "네트워크를 사용할 수 없어 변경을 로컬에 보관했습니다.");
                    } catch(JSONException e) {
                        releaseClaimedFrom(pending,i);
                        return result("blocked", "AutoSync 응답 형식을 확인하세요.");
                    }
                }
                pull(endpoint,token);
                if(store.conflictCount()>0) return result("conflict", "충돌한 작업은 양쪽 내용을 보존했습니다. 선택해서 해결하세요.");
                return result(store.pendingCount()>0?"pending":"synced", store.pendingCount()>0?"전송 대기 중":"동기화됨");
            } catch(UnauthorizedException e) {
                return result("needs-credential", "서버 인증이 필요합니다.");
            } catch(IOException e) {
                return result("offline", "서버 오프라인 · 변경은 이 기기에 보관됩니다.");
            } catch(JSONException|IllegalArgumentException e) {
                return result("blocked", "AutoSync 설정 또는 서버 응답을 확인하세요.");
            }
        }
    }

    private void releaseClaimedFrom(JSONArray operations,int first) {
        for(int i=first;i<operations.length();i++) {
            JSONObject operation=operations.optJSONObject(i);
            if(operation!=null) store.releaseOperation(operation.optString("op_id"));
        }
    }

    private void pull(String endpoint,String token) throws IOException,JSONException,UnauthorizedException {
        long cursor=store.cursor();
        String url=base(endpoint)+"/v1/apps/"+encode(AppIdentity.APP_ID)+"/state"+(cursor<0?"":"?since="+cursor);
        Response response=request("GET",url,token,null,null,null);
        if(response.code==200) { store.applyServerState(response.body); return; }
        if(response.code==409&&"snapshot_required".equals(response.body.optString("error"))) { store.applyServerState(response.body); return; }
        if(response.code==401||response.code==403) throw new UnauthorizedException();
        throw new IOException("AutoSync state request returned HTTP "+response.code);
    }

    private Response request(String method,String url,String token,String body,Long expected,String operationId) throws IOException,JSONException,UnauthorizedException {
        HttpURLConnection connection=(HttpURLConnection)URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Authorization","Bearer "+token); connection.setRequestProperty("Accept","application/json");
        if(expected!=null) connection.setRequestProperty("If-Match",Long.toString(expected));
        if(operationId!=null) connection.setRequestProperty("Idempotency-Key",operationId);
        if(body!=null) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
        }
        try {
            int code=connection.getResponseCode();
            InputStream stream=code>=400?connection.getErrorStream():connection.getInputStream();
            JSONObject parsed=new JSONObject(readLimited(stream));
            if(code==401||code==403) throw new UnauthorizedException();
            return new Response(code,parsed);
        } finally { connection.disconnect(); }
    }

    private static String readLimited(InputStream input) throws IOException {
        if(input==null) return "{}";
        try(InputStream in=input;ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count,total=0;
            while((count=in.read(buffer))!=-1){total+=count;if(total>MAX_RESPONSE_BYTES)throw new IOException("AutoSync response is too large");output.write(buffer,0,count);}
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static void validateEndpoint(String endpoint,boolean debugBuild) {
        URI uri=URI.create(endpoint);
        String scheme=uri.getScheme(),host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))
            throw new IllegalArgumentException("Use a server origin without a path, query, or embedded credentials.");
        if("https".equalsIgnoreCase(scheme))return;
        if(!"http".equalsIgnoreCase(scheme))throw new IllegalArgumentException("AutoSync requires HTTPS outside local tests.");
        boolean loopback="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host);
        boolean emulator=debugBuild&&"10.0.2.2".equals(host);
        if(!loopback&&!emulator)throw new IllegalArgumentException("Non-loopback AutoSync servers require trusted HTTPS.");
    }

    private boolean isDebuggable(){return (context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
    private static String base(String value){return value.endsWith("/")?value.substring(0,value.length()-1):value;}
    private static String encode(String value){
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);StringBuilder out=new StringBuilder(bytes.length);
        for(byte item:bytes){int b=item&0xff;boolean safe=(b>='a'&&b<='z')||(b>='A'&&b<='Z')||(b>='0'&&b<='9')||b=='-'||b=='_'||b=='.'||b=='~';
            if(safe)out.append((char)b);else out.append('%').append("0123456789ABCDEF".charAt((b>>>4)&15)).append("0123456789ABCDEF".charAt(b&15));}
        return out.toString();
    }
    private Result result(String status,String detail){return new Result(status,detail,store.pendingCount(),store.conflictCount());}

    private static final class Response{final int code;final JSONObject body;Response(int code,JSONObject body){this.code=code;this.body=body;}}
    private static final class UnauthorizedException extends Exception{private static final long serialVersionUID=1L;}
    public static final class Result{public final String status,detail;public final int pending,conflicts;Result(String s,String d,int p,int c){status=s;detail=d;pending=p;conflicts=c;}}
}
