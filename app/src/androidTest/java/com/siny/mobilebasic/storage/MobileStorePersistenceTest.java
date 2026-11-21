package com.siny.mobilebasic.storage;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.siny.mobilebasic.AppIdentity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the real SQLite store on Android without a product-facing demo screen. */
@RunWith(AndroidJUnit4.class)
public final class MobileStorePersistenceTest {
    private Context context;
    private String databaseName;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        databaseName = "autosync-" + AppIdentity.APP_ID + ".db";
        context.deleteDatabase(databaseName);
    }

    @After public void tearDown() {
        context.deleteDatabase(databaseName);
    }

    @Test
    public void testGenericRecordSurvivesStoreCloseAndReopenAndDeleteIsQueued() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("kind", "smoke-probe");
        payload.put("count", 7);
        payload.put("nested", new JSONObject().put("ready", true));

        MobileStore first = new MobileStore(context);
        first.saveRecord("probe-record", payload);
        assertEquals(1, first.pendingCount());
        first.close();

        MobileStore reopened = new MobileStore(context);
        MobileStore.Record record = reopened.getRecord("probe-record");
        assertNotNull(record);
        assertFalse(record.deleted);
        assertEquals("smoke-probe", record.payload.getString("kind"));
        assertEquals(7, record.payload.getInt("count"));
        assertTrue(record.payload.getJSONObject("nested").getBoolean("ready"));
        assertEquals("probe-record", reopened.records().get(0).id);

        reopened.deleteRecord("probe-record");
        MobileStore.Record tombstone = reopened.getRecord("probe-record");
        assertNotNull(tombstone);
        assertTrue(tombstone.deleted);
        assertEquals(1, reopened.pendingCount());
        reopened.close();
    }

    @Test
    public void testTaskCompatibilityWrapperUsesGenericRecordStorage() throws Exception {
        MobileStore store = new MobileStore(context);
        String id = store.saveTask("legacy-task", "kept", true);
        assertEquals("legacy-task", id);
        MobileStore.Record record = store.getRecord(id);
        assertNotNull(record);
        assertEquals("kept", record.payload.getString("text"));
        assertTrue(record.payload.getBoolean("done"));
        store.close();
    }

    @Test
    public void testRollbackTaskWriteRestoresAdditiveFieldsFromGuardAndPendingOutbox() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("text", "before");
        payload.put("done", false);
        payload.put("dueAt", "tomorrow");
        MobileStore first = new MobileStore(context);
        first.saveRecord("task-rollback", payload);
        first.close();

        SQLiteDatabase legacy = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        ContentValues oldTaskPayload = new ContentValues();
        oldTaskPayload.put("payload", "{\"text\":\"edited by old app\",\"done\":true}");
        legacy.update("records", oldTaskPayload, "record_id=?", new String[]{"task-rollback"});
        legacy.update("outbox", oldTaskPayload, "record_id=? AND state='pending'", new String[]{"task-rollback"});
        legacy.close();

        MobileStore upgraded = new MobileStore(context);
        MobileStore.Record restored = upgraded.getRecord("task-rollback");
        assertNotNull(restored);
        assertEquals("edited by old app", restored.payload.getString("text"));
        assertTrue(restored.payload.getBoolean("done"));
        assertEquals("tomorrow", restored.payload.getString("dueAt"));
        try (Cursor event = upgraded.getReadableDatabase().rawQuery(
                "SELECT payload FROM outbox WHERE record_id=? AND state='pending'", new String[]{"task-rollback"})) {
            assertTrue(event.moveToFirst());
            assertEquals("tomorrow", new JSONObject(event.getString(0)).getString("dueAt"));
        }
        upgraded.close();
    }

    @Test
    public void testOlderRemotePayloadCannotEraseGuardedFields() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("kind", "profile");
        payload.put("name", "new value");
        payload.put("dueAt", "tomorrow");
        MobileStore store = new MobileStore(context);
        store.saveRecord("profile-main", payload);
        ContentValues synced = new ContentValues();
        synced.put("state", "synced");
        store.getWritableDatabase().update("outbox", synced, "record_id=?", new String[]{"profile-main"});
        JSONObject oldPayload = new JSONObject().put("kind", "profile").put("name", "old peer");
        JSONObject remote = new JSONObject().put("record_id", "profile-main").put("revision", 2)
                .put("payload", oldPayload).put("deleted", false).put("updated_utc", "2026-11-21T00:00:00Z");
        JSONObject state = new JSONObject().put("app_id", AppIdentity.APP_ID).put("cursor", 3)
                .put("records", new org.json.JSONArray().put(remote));
        store.applyServerState(state);
        MobileStore.Record merged = store.getRecord("profile-main");
        assertEquals("old peer", merged.payload.getString("name"));
        assertEquals("tomorrow", merged.payload.getString("dueAt"));
        JSONObject edited = new JSONObject(merged.payload.toString());
        edited.put("name", "edited after old peer");
        store.saveRecord("profile-main", edited);
        try (Cursor event = store.getReadableDatabase().rawQuery(
                "SELECT payload FROM outbox WHERE record_id=? AND state='pending'", new String[]{"profile-main"})) {
            assertTrue(event.moveToFirst());
            assertEquals("tomorrow", new JSONObject(event.getString(0)).getString("dueAt"));
        }
        store.close();
    }

    @Test
    public void testExplicitJsonNullClearsGuardedFieldAndStaysClearedAfterReopen() throws Exception {
        MobileStore store = new MobileStore(context);
        JSONObject initial = new JSONObject().put("text", "kept").put("dueAt", "tomorrow");
        store.saveRecord("null-clear", initial);
        store.clearRecordField("null-clear", "dueAt");
        assertTrue(store.getRecord("null-clear").payload.isNull("dueAt"));
        try (Cursor event = store.getReadableDatabase().rawQuery(
                "SELECT payload FROM outbox WHERE record_id=? AND state='pending'", new String[]{"null-clear"})) {
            assertTrue(event.moveToFirst());
            assertTrue(new JSONObject(event.getString(0)).isNull("dueAt"));
        }
        store.close();

        MobileStore reopened = new MobileStore(context);
        MobileStore.Record restored = reopened.getRecord("null-clear");
        assertNotNull(restored);
        assertTrue(restored.payload.has("dueAt"));
        assertTrue(restored.payload.isNull("dueAt"));
        assertEquals("kept", restored.payload.getString("text"));
        reopened.close();
    }

    @Test
    public void testRemoteGuardMergePreservesNestedFieldsAndDistinguishesNullFromString() throws Exception {
        MobileStore store = new MobileStore(context);
        JSONObject settings = new JSONObject().put("theme", "old").put("newFlag", true);
        store.saveRecord("nested-null", new JSONObject().put("dueAt", "tomorrow")
                .put("label", "before").put("settings", settings)
                .put("items", new JSONObject().put("oldShape", true)));
        ContentValues synced = new ContentValues();
        synced.put("state", "synced");
        store.getWritableDatabase().update("outbox", synced, "record_id=?", new String[]{"nested-null"});

        JSONObject olderSettings = new JSONObject().put("theme", "new");
        org.json.JSONArray replacementItems = new org.json.JSONArray().put(JSONObject.NULL).put("null");
        JSONObject remote = new JSONObject().put("record_id", "nested-null").put("revision", 2)
                .put("payload", new JSONObject().put("dueAt", JSONObject.NULL)
                        .put("label", "null").put("settings", olderSettings).put("items", replacementItems))
                .put("deleted", false).put("updated_utc", "2026-11-21T00:00:00Z");
        JSONObject state = new JSONObject().put("app_id", AppIdentity.APP_ID).put("cursor", 1)
                .put("records", new org.json.JSONArray().put(remote));
        store.applyServerState(state);

        JSONObject merged = store.getRecord("nested-null").payload;
        assertTrue(merged.has("dueAt"));
        assertTrue(merged.isNull("dueAt"));
        assertEquals("null", merged.getString("label"));
        assertEquals("new", merged.getJSONObject("settings").getString("theme"));
        assertTrue(merged.getJSONObject("settings").getBoolean("newFlag"));
        assertEquals(2, merged.getJSONArray("items").length());
        assertTrue(merged.getJSONArray("items").isNull(0));
        assertEquals("null", merged.getJSONArray("items").getString(1));
        store.close();

        for (int i = 0; i < 5; i++) {
            MobileStore reopened = new MobileStore(context);
            JSONObject restored = reopened.getRecord("nested-null").payload;
            assertTrue(restored.isNull("dueAt"));
            assertEquals("null", restored.getString("label"));
            assertTrue(restored.getJSONObject("settings").getBoolean("newFlag"));
            assertEquals(2, restored.getJSONArray("items").length());
            assertTrue(restored.getJSONArray("items").isNull(0));
            assertEquals("null", restored.getJSONArray("items").getString(1));
            reopened.close();
        }
    }

    @Test
    public void testTombstoneSurvivesReopenAndReusedIdDoesNotInheritOldFields() throws Exception {
        MobileStore store = new MobileStore(context);
        store.saveRecord("reused-id", new JSONObject().put("text", "old").put("dueAt", "old value"));
        store.deleteRecord("reused-id");
        store.close();

        for (int i = 0; i < 3; i++) {
            MobileStore reopened = new MobileStore(context);
            assertTrue(reopened.getRecord("reused-id").deleted);
            reopened.close();
        }

        MobileStore reused = new MobileStore(context);
        reused.saveRecord("reused-id", new JSONObject().put("text", "new"));
        MobileStore.Record fresh = reused.getRecord("reused-id");
        assertFalse(fresh.deleted);
        assertEquals("new", fresh.payload.getString("text"));
        assertFalse(fresh.payload.has("dueAt"));
        reused.close();
    }

    @Test
    public void testLegacyHardDeleteMarksGuardBeforeRecordIdCanBeReused() throws Exception {
        MobileStore initial = new MobileStore(context);
        initial.saveRecord("hard-delete-id", new JSONObject().put("text", "old").put("dueAt", "must not leak"));
        initial.close();

        SQLiteDatabase legacy = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        legacy.delete("records", "record_id=?", new String[]{"hard-delete-id"});
        ContentValues reusedByOlderApp = new ContentValues();
        reusedByOlderApp.put("record_id", "hard-delete-id");
        reusedByOlderApp.put("revision", 1);
        reusedByOlderApp.put("payload", new JSONObject().put("text", "fresh legacy identity").toString());
        reusedByOlderApp.put("deleted", 0);
        reusedByOlderApp.put("updated_utc", "2026-11-21T00:00:00Z");
        legacy.insertOrThrow("records", null, reusedByOlderApp);
        legacy.close();

        MobileStore reopened = new MobileStore(context);
        MobileStore.Record fresh = reopened.getRecord("hard-delete-id");
        assertNotNull(fresh);
        assertFalse(fresh.deleted);
        assertEquals("fresh legacy identity", fresh.payload.getString("text"));
        assertFalse(fresh.payload.has("dueAt"));
        reopened.close();
    }

    @Test
    public void testGuardCanUpgradeEarlierAdditiveGuardTableWithoutChangingDatabaseVersion() throws Exception {
        MobileStore store = new MobileStore(context);
        store.saveRecord("legacy-guard", new JSONObject().put("text", "new").put("dueAt", "preserve"));
        store.saveRecord("deleted-guard", new JSONObject().put("text", "deleted").put("dueAt", "old"));
        store.deleteRecord("deleted-guard");
        store.close();

        SQLiteDatabase legacy = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        legacy.execSQL("DROP TABLE record_schema_guard");
        legacy.execSQL("CREATE TABLE record_schema_guard(record_id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        ContentValues oldGuard = new ContentValues();
        oldGuard.put("record_id", "legacy-guard");
        oldGuard.put("payload", new JSONObject().put("text", "new").put("dueAt", "preserve").toString());
        legacy.insertOrThrow("record_schema_guard", null, oldGuard);
        ContentValues oldTombstoneGuard = new ContentValues();
        oldTombstoneGuard.put("record_id", "deleted-guard");
        oldTombstoneGuard.put("payload", new JSONObject().put("text", "deleted").put("dueAt", "old").toString());
        legacy.insertOrThrow("record_schema_guard", null, oldTombstoneGuard);
        ContentValues orphanGuard = new ContentValues();
        orphanGuard.put("record_id", "orphan-guard");
        orphanGuard.put("payload", new JSONObject().put("dueAt", "must not leak").toString());
        legacy.insertOrThrow("record_schema_guard", null, orphanGuard);
        ContentValues oldPayload = new ContentValues();
        oldPayload.put("payload", new JSONObject().put("text", "edited by compatible old app").toString());
        legacy.update("records", oldPayload, "record_id=?", new String[]{"legacy-guard"});
        legacy.execSQL("CREATE TRIGGER reject_guard_backfill BEFORE UPDATE ON record_schema_guard "
                + "BEGIN SELECT RAISE(ABORT,'injected guard backfill failure'); END");
        legacy.close();

        boolean migrationFailed = false;
        try {
            new MobileStore(context).getReadableDatabase();
        } catch (RuntimeException expected) {
            migrationFailed = true;
        }
        assertTrue("The injected guard migration error should reach the caller.", migrationFailed);

        SQLiteDatabase retry = SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        boolean rolledBackColumn = false;
        try (Cursor columns = retry.rawQuery("PRAGMA table_info(record_schema_guard)", null)) {
            while (columns.moveToNext()) if ("deleted".equals(columns.getString(1))) rolledBackColumn = true;
        }
        assertFalse("ALTER and backfill must roll back together after migration failure.", rolledBackColumn);
        retry.execSQL("DROP TRIGGER reject_guard_backfill");
        retry.close();

        MobileStore upgraded = new MobileStore(context);
        MobileStore.Record restored = upgraded.getRecord("legacy-guard");
        assertEquals("edited by compatible old app", restored.payload.getString("text"));
        assertEquals("preserve", restored.payload.getString("dueAt"));
        assertTrue(upgraded.getRecord("deleted-guard").deleted);
        upgraded.saveRecord("orphan-guard", new JSONObject().put("text", "fresh identity"));
        assertFalse(upgraded.getRecord("orphan-guard").payload.has("dueAt"));
        boolean hasDeleted = false;
        try (Cursor columns = upgraded.getReadableDatabase().rawQuery("PRAGMA table_info(record_schema_guard)", null)) {
            while (columns.moveToNext()) if ("deleted".equals(columns.getString(1))) hasDeleted = true;
        }
        assertTrue(hasDeleted);
        upgraded.close();
    }

    @Test
    public void testRecordAndOutboxStayAtomicWhenQueueInsertFails() throws Exception {
        MobileStore store = new MobileStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'injected queue failure'); END");
        boolean failed = false;
        try {
            store.saveRecord("atomic-record", new JSONObject().put("value", "must rollback"));
        } catch (RuntimeException expected) {
            failed = true;
        }
        assertTrue("The injected outbox error should reach the caller.", failed);
        assertNull(store.getRecord("atomic-record"));
        try (Cursor guard = db.rawQuery("SELECT 1 FROM record_schema_guard WHERE record_id='atomic-record'", null)) {
            assertFalse(guard.moveToFirst());
        }
        try (Cursor outbox = db.rawQuery("SELECT 1 FROM outbox WHERE record_id='atomic-record'", null)) {
            assertFalse(outbox.moveToFirst());
        }
        store.close();
    }
}
