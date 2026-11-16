package com.siny.mobilebasic;

import com.siny.mobilebasic.modules.AutoSyncModule;
import com.siny.mobilebasic.modules.AutoUpdateModule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class ModuleContractTest {
    @Test public void programNamesGetStableAndDistinctIds(){
        assertEquals(AppIdentity.slugForName("Demo App"),AppIdentity.slugForName("demo app"));
        assertNotEquals(AppIdentity.slugForName("Demo App"),AppIdentity.slugForName("Demo-App"));
        assertEquals("demo-alarm-60f4878cb0",AppIdentity.slugForName("Demo Alarm"));
        assertTrue(AppIdentity.isValidAppId(AppIdentity.slugForName("새 알람")));
        assertEquals("com.siny.apps."+AppIdentity.slugForName("Demo App").replace('-','_'),AppIdentity.applicationIdForName("Demo App"));
    }

    @Test public void autosyncRequiresHttpsOutsideLoopback(){
        AutoSyncModule.validateEndpoint("https://sync.example.test",false);
        AutoSyncModule.validateEndpoint("http://127.0.0.1:18765",false);
        AutoSyncModule.validateEndpoint("http://10.0.2.2:18765",true);
        assertThrows(IllegalArgumentException.class,()->AutoSyncModule.validateEndpoint("http://192.168.1.10:18765",false));
        assertThrows(IllegalArgumentException.class,()->AutoSyncModule.validateEndpoint("http://192.168.1.10:18765",true));
        assertThrows(IllegalArgumentException.class,()->AutoSyncModule.validateEndpoint("https://sync.example.test/v1",false));
        assertThrows(IllegalArgumentException.class,()->AutoSyncModule.validateEndpoint("https://user:pass@sync.example.test",false));
    }

    @Test public void updateSourceRequiresHttpsOutsideLocalDebug(){
        assertTrue(AutoUpdateModule.isSecureUrl("https://updates.example.test/apps/app/manifest.json",false));
        assertTrue(AutoUpdateModule.isSecureUrl("http://127.0.0.1:8080/manifest.json",false));
        assertTrue(AutoUpdateModule.isSecureUrl("http://10.0.2.2:8080/manifest.json",true));
        assertFalse(AutoUpdateModule.isSecureUrl("http://192.168.1.10/manifest.json",false));
        assertTrue(AutoUpdateModule.isSecureUrl("http://192.168.1.10/manifest.json",true));
        assertTrue(AutoUpdateModule.isSecureUrl("http://172.20.1.10/manifest.json",true));
        assertFalse(AutoUpdateModule.isSecureUrl("http://8.8.8.8/manifest.json",true));
        assertFalse(AutoUpdateModule.isSecureUrl("https://user:pass@updates.example.test",false));
    }

    @Test public void appAndPackagedManifestsDeclareBothPlatformAdapters(){
        try{
            Path project=Path.of("").toAbsolutePath();
            JSONObject modules=read(project.resolve("app-modules.json"));
            JSONObject packagedModules=read(project.resolve("app/src/main/assets/app-modules.json"));
            JSONObject app=read(project.resolve("mobile-app.json"));
            JSONObject packagedApp=read(project.resolve("app/src/main/assets/mobile-app.json"));
            JSONObject version=read(project.resolve("version.json"));
            JSONObject packagedVersion=read(project.resolve("app/src/main/assets/version.json"));
            assertEquals(modules.toString(),packagedModules.toString());
            assertEquals(app.toString(),packagedApp.toString());
            assertEquals(version.toString(),packagedVersion.toString());
            assertEquals(AppIdentity.APP_ID,modules.getString("app_id"));
            assertEquals(AppIdentity.APP_ID,app.getString("app_id"));
            assertEquals(AppIdentity.APP_ID,version.getString("app_id"));
            assertFalse("A template must not bake in a repository",app.has("repository"));
            assertEquals(app.getString("application_id"),applicationIdInGradle(project.resolve("app/build.gradle")));
            JSONArray declared=modules.getJSONArray("modules");boolean update=false,sync=false;
            for(int i=0;i<declared.length();i++){JSONObject item=declared.getJSONObject(i);if("AutoUpdate".equals(item.optString("module_id"))&&item.optInt("api_major")==1)update=true;if("AutoSync".equals(item.optString("module_id"))&&item.optInt("api_major")==1)sync=true;}
            assertTrue(update);assertTrue(sync);
        }catch(Exception e){fail(e.toString());}
    }

    private static JSONObject read(Path path) throws Exception{
        return new JSONObject(Files.readString(path,StandardCharsets.UTF_8));
    }

    private static String applicationIdInGradle(Path path) throws Exception{
        String build=Files.readString(path,StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("applicationId '([^']+)'").matcher(build);
        if(!matcher.find())throw new AssertionError("applicationId missing from app/build.gradle");
        return matcher.group(1);
    }
}
