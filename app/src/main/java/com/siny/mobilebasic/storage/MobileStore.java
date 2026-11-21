package com.siny.mobilebasic.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.siny.mobilebasic.AppIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Per-app local records and durable AutoSync outbox. Credentials are stored elsewhere. */
public final class MobileStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 1;
    private final String appId;

    public MobileStore(Context context) {
        super(context.getApplicationContext(), "autosync-" + AppIdentity.APP_ID + ".db", null, DB_VERSION);
        appId = AppIdentity.APP_ID;
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(record_id TEXT PRIMARY KEY,revision INTEGER NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL,updated_utc TEXT NOT NULL)");
        db.execSQL("CREATE TABLE outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT,op_id TEXT UNIQUE NOT NULL,record_id TEXT NOT NULL,expected_revision INTEGER NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL,state TEXT NOT NULL,response TEXT,created_utc INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX outbox_state_order ON outbox(state,seq)");
        db.execSQL("CREATE INDEX outbox_record_state ON outbox(record_id,state,seq)");
        db.execSQL("CREATE TABLE sync_state(app_id TEXT PRIMARY KEY,cursor INTEGER NOT NULL)");
        db.execSQL("INSERT INTO sync_state(app_id,cursor) VALUES('" + appId + "',-1)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != newVersion) throw new IllegalStateException("No migration path for mobile data version " + oldVersion);
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        ensureSchemaGuard(db);
        ContentValues pending=new ContentValues(); pending.put("state", "pending");
        db.update("outbox", pending, "state='sending'", null);
        restoreGuardedPayloads(db);
    }

    /** Adds an app-defined JSON record and queues it for AutoSync when configured. */
    public synchronized String addRecord(JSONObject payload) {
        return saveRecord("r" + UUID.randomUUID().toString().replace("-", ""), payload);
    }

    /** Stores arbitrary app data in the existing records/outbox schema. */
    public synchronized String saveRecord(String recordId, JSONObject payload) {
        if (recordId == null || !recordId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid record ID");
        if (payload == null) throw new IllegalArgumentException("Record payload must be a JSON object");
        final JSONObject normalized;
        try { normalized = new JSONObject(payload.toString()); }
        catch (JSONException invalid) { throw new IllegalArgumentException("Invalid JSON record payload", invalid); }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureNoConflict(db, recordId);
            JSONObject complete = mergeWithGuard(db, recordId, normalized, false);
            writeLocal(db, recordId, complete, false);
            queueWrite(db, recordId, complete, false);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return recordId;
    }

    /** Returns a local snapshot, including its tombstone/conflict state, or null if absent. */
    public synchronized Record getRecord(String recordId) {
        try (Cursor row = getReadableDatabase().rawQuery(
                "SELECT r.record_id,r.revision,r.payload,r.deleted,EXISTS(SELECT 1 FROM outbox o WHERE o.record_id=r.record_id AND o.state='conflict') "
                        + "FROM records r WHERE r.record_id=?", new String[]{recordId})) {
            if (!row.moveToFirst()) return null;
            try {
                return new Record(row.getString(0), new JSONObject(row.getString(2)), row.getLong(1),
                        row.getInt(3) != 0, row.getInt(4) != 0);
            } catch (JSONException malformed) {
                throw new IllegalStateException("Stored AutoSync record is not valid JSON", malformed);
            }
        }
    }

    /** Returns app records; deleted rows remain visible only while a conflict needs resolution. */
    public synchronized List<Record> records() {
        ArrayList<Record> items = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT r.record_id,r.revision,r.payload,r.deleted,EXISTS(SELECT 1 FROM outbox o WHERE o.record_id=r.record_id AND o.state='conflict') "
                        + "FROM records r WHERE r.deleted=0 OR EXISTS(SELECT 1 FROM outbox o WHERE o.record_id=r.record_id AND o.state='conflict') "
                        + "ORDER BY r.updated_utc DESC,r.record_id", null)) {
            while (rows.moveToNext()) {
                try {
                    items.add(new Record(rows.getString(0), new JSONObject(rows.getString(2)), rows.getLong(1),
                            rows.getInt(3) != 0, rows.getInt(4) != 0));
                } catch (JSONException malformed) {
                    throw new IllegalStateException("Stored AutoSync record is not valid JSON", malformed);
                }
            }
        }
        return items;
    }

    public synchronized void deleteRecord(String recordId) {
        if (recordId == null || !recordId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid record ID");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensureNoConflict(db, recordId);
            JSONObject payload = localPayload(db, recordId);
            if (payload == null) {
                db.setTransactionSuccessful();
                return;
            }
            JSONObject complete = mergeWithGuard(db, recordId, payload, true);
            writeLocal(db, recordId, complete, true);
            queueWrite(db, recordId, complete, true);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Explicitly clears one optional field without treating omitted keys as deletion. */
    public synchronized String clearRecordField(String recordId, String fieldName) {
        if (fieldName == null || fieldName.trim().isEmpty())
            throw new IllegalArgumentException("Record field name is required");
        Record current = getRecord(recordId);
        if (current == null || current.deleted) throw new IllegalArgumentException("Record does not exist.");
        JSONObject payload;
        try {
            payload = new JSONObject(current.payload.toString());
            payload.put(fieldName, JSONObject.NULL);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Stored AutoSync record is not valid JSON", impossible);
        }
        return saveRecord(recordId, payload);
    }

    public synchronized String addTask(String text) { return saveTask("r"+UUID.randomUUID().toString().replace("-", ""), text, false); }

    public synchronized String saveTask(String recordId, String text, boolean done) {
        String title = text == null ? "" : text.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Task text cannot be empty.");
        JSONObject payload = mergeWithGuard(getReadableDatabase(), recordId, new JSONObject(), false);
        try { payload.put("text", title); payload.put("done", done); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return saveRecord(recordId, payload);
    }

    public synchronized void deleteTask(String recordId) {
        deleteRecord(recordId);
    }

    public synchronized List<Task> tasks() {
        ArrayList<Task> items = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor rows = db.rawQuery("SELECT r.record_id,r.revision,r.payload,r.deleted,EXISTS(SELECT 1 FROM outbox o WHERE o.record_id=r.record_id AND o.state='conflict'),COALESCE((SELECT o.response FROM outbox o WHERE o.record_id=r.record_id AND o.state='conflict' ORDER BY o.seq LIMIT 1),'{}') FROM records r ORDER BY r.updated_utc DESC,r.record_id", null)) {
            while (rows.moveToNext()) {
                boolean deleted = rows.getInt(3) != 0;
                boolean conflict=rows.getInt(4)!=0;
                if (deleted&&!conflict) continue;
                try {
                    JSONObject payload = new JSONObject(rows.getString(2));
                    JSONObject response=new JSONObject(rows.getString(5));JSONObject current=response.optJSONObject("current");JSONObject serverPayload=current==null?null:current.optJSONObject("payload");
                    String serverText=current==null?(current==null&&conflict?"(서버에 아직 없음)":""):current.optBoolean("deleted",false)?"(서버에서 삭제됨)":serverPayload==null?"":serverPayload.optString("text","");
                    items.add(new Task(rows.getString(0), payload.optString("text", ""), payload.optBoolean("done", false), rows.getInt(1), conflict, serverText));
                } catch (JSONException ignored) { }
            }
        }
        return items;
    }

    public synchronized int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state IN ('pending','sending')", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized int conflictCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT record_id) FROM outbox WHERE state='conflict'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized long cursor() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT cursor FROM sync_state WHERE app_id=?", new String[]{appId})) {
            return c.moveToFirst() ? c.getLong(0) : -1L;
        }
    }

    /** Atomically claims queued operations so foreground and background runs cannot send the same row. */
    public synchronized JSONArray claimPending() {
        JSONArray result = new JSONArray();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try (Cursor c = db.rawQuery("SELECT seq,op_id,record_id,expected_revision,payload,deleted FROM outbox WHERE state='pending' ORDER BY seq LIMIT 64", null)) {
            while (c.moveToNext()) {
                ContentValues cv = new ContentValues(); cv.put("state", "sending");
                db.update("outbox", cv, "op_id=? AND state='pending'", new String[]{c.getString(1)});
                JSONObject item = new JSONObject();
                item.put("seq", c.getLong(0)); item.put("op_id", c.getString(1)); item.put("record_id", c.getString(2));
                item.put("expected_revision", c.getLong(3)); item.put("payload", new JSONObject(c.getString(4))); item.put("deleted", c.getInt(5) != 0);
                result.put(item);
            }
            db.setTransactionSuccessful();
        } catch (JSONException e) { throw new IllegalStateException("Invalid local outbox JSON", e); }
        finally { db.endTransaction(); }
        return result;
    }

    public synchronized void releaseOperation(String operationId) {
        ContentValues cv = new ContentValues(); cv.put("state", "pending");
        getWritableDatabase().update("outbox", cv, "op_id=? AND state='sending'", new String[]{operationId});
    }

    public synchronized void applyServerState(JSONObject state) throws JSONException {
        if (!appId.equals(state.optString("app_id"))) throw new JSONException("AutoSync app_id mismatch");
        long nextCursor = state.getLong("cursor");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            JSONArray records = state.optJSONArray("records");
            if (records != null) {
                for (int i = 0; i < records.length(); i++) applyRemoteRow(db, records.getJSONObject(i));
            } else {
                JSONArray events = state.optJSONArray("events");
                if (events != null) for (int i = 0; i < events.length(); i++) applyRemoteRow(db, events.getJSONObject(i));
            }
            ContentValues cursorValues = new ContentValues(); cursorValues.put("app_id", appId); cursorValues.put("cursor", nextCursor);
            db.insertWithOnConflict("sync_state", null, cursorValues, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void markSynced(JSONObject operation, JSONObject response) throws JSONException {
        String op = operation.getString("op_id"), rid = operation.getString("record_id");
        long revision = response.getLong("revision"), seq = operation.getLong("seq");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues done = new ContentValues(); done.put("state", "synced"); done.put("response", response.toString());
            db.update("outbox", done, "op_id=? AND state='sending'", new String[]{op});
            ContentValues current = new ContentValues(); current.put("revision", revision);
            current.put("updated_utc", response.optString("updated_utc", ""));
            db.update("records", current, "record_id=?", new String[]{rid});
            ContentValues pending = new ContentValues(); pending.put("expected_revision", revision);
            db.update("outbox", pending, "record_id=? AND state IN ('pending','sending') AND seq>?", new String[]{rid, Long.toString(seq)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void markConflict(JSONObject operation, JSONObject response) throws JSONException {
        String op = operation.getString("op_id"), rid = operation.getString("record_id");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues conflict = new ContentValues(); conflict.put("state", "conflict"); conflict.put("response", response.toString());
            db.update("outbox", conflict, "op_id=?", new String[]{op});
            ContentValues blocked = new ContentValues(); blocked.put("state", "conflict");
            blocked.put("response", new JSONObject().put("error", "blocked_by_prior_conflict").toString());
            db.update("outbox", blocked, "record_id=? AND seq>? AND state IN ('pending','sending')", new String[]{rid, Long.toString(operation.getLong("seq"))});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void resolveConflict(String recordId, boolean keepLocal) throws JSONException {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String localBody = null; boolean localDeleted = false;
            try (Cursor local = db.rawQuery("SELECT payload,deleted FROM records WHERE record_id=?", new String[]{recordId})) {
                if (!local.moveToFirst()) throw new IllegalArgumentException("작업을 찾을 수 없습니다.");
                localBody = local.getString(0); localDeleted = local.getInt(1) != 0;
            }
            JSONObject conflict = null;
            try (Cursor c = db.rawQuery("SELECT response FROM outbox WHERE record_id=? AND state='conflict' ORDER BY seq LIMIT 1", new String[]{recordId})) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("해결할 충돌이 없습니다.");
                conflict = new JSONObject(c.getString(0));
            }
            JSONObject current = conflict.optJSONObject("current");
            long expected = current == null ? 0L : current.optLong("revision", 0L);
            JSONObject chosen = keepLocal || current == null ? new JSONObject(localBody) : current.optJSONObject("payload");
            boolean deleted = keepLocal ? localDeleted : current == null || current.optBoolean("deleted", false);
            if (chosen == null) chosen = new JSONObject();
            chosen = mergeWithGuard(db, recordId, chosen, deleted);
            ContentValues resolution = new ContentValues(); resolution.put("state", "resolved");
            db.update("outbox", resolution, "record_id=? AND state='conflict'", new String[]{recordId});
            String op = UUID.randomUUID().toString().replace("-", "");
            ContentValues next = new ContentValues(); next.put("op_id", op); next.put("record_id", recordId); next.put("expected_revision", expected);
            next.put("payload", chosen.toString()); next.put("deleted", deleted ? 1 : 0); next.put("state", "pending"); next.put("created_utc", System.currentTimeMillis());
            db.insertOrThrow("outbox", null, next);
            ContentValues updateLocal = new ContentValues(); updateLocal.put("revision", expected); updateLocal.put("payload", chosen.toString()); updateLocal.put("deleted", deleted ? 1 : 0);
            db.update("records", updateLocal, "record_id=?", new String[]{recordId});
            upsertSchemaGuard(db, recordId, chosen, deleted);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void applyRemoteRow(SQLiteDatabase db, JSONObject item) throws JSONException {
        String rid = item.getString("record_id");
        try (Cursor pending = db.rawQuery("SELECT 1 FROM outbox WHERE record_id=? AND state IN ('pending','sending','conflict') LIMIT 1", new String[]{rid})) {
            if (pending.moveToFirst()) return;
        }
        boolean deleted = item.optBoolean("deleted");
        JSONObject payload = mergeWithGuard(db, rid, item.getJSONObject("payload"), deleted);
        ContentValues remote = new ContentValues(); remote.put("record_id", rid); remote.put("revision", item.getLong("revision"));
        remote.put("payload", payload.toString()); remote.put("deleted", deleted ? 1 : 0);
        remote.put("updated_utc", item.optString("updated_utc", ""));
        db.insertWithOnConflict("records", null, remote, SQLiteDatabase.CONFLICT_REPLACE);
        upsertSchemaGuard(db, rid, payload, deleted);
    }

    private void writeLocal(SQLiteDatabase db, String rid, JSONObject payload, boolean deleted) {
        ContentValues values = new ContentValues(); values.put("record_id", rid); values.put("payload", payload.toString());
        values.put("deleted", deleted ? 1 : 0); values.put("updated_utc", Long.toString(System.currentTimeMillis()));
        try (Cursor old = db.rawQuery("SELECT revision FROM records WHERE record_id=?", new String[]{rid})) { values.put("revision", old.moveToFirst() ? old.getLong(0) : 0L); }
        db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        upsertSchemaGuard(db, rid, payload, deleted);
    }

    private JSONObject localPayload(SQLiteDatabase db, String rid) {
        try (Cursor c = db.rawQuery("SELECT payload FROM records WHERE record_id=?", new String[]{rid})) {
            if (!c.moveToFirst()) return null;
            return new JSONObject(c.getString(0));
        } catch (JSONException e) { throw new IllegalStateException("Stored AutoSync record is not valid JSON", e); }
    }

    private void ensureNoConflict(SQLiteDatabase db, String rid) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM outbox WHERE record_id=? AND state='conflict' LIMIT 1", new String[]{rid})) {
            if (c.moveToFirst()) throw new IllegalStateException("Resolve the AutoSync conflict before editing this record.");
        }
    }

    private void ensureSchemaGuard(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS record_schema_guard(record_id TEXT PRIMARY KEY,payload TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)");
            boolean hasDeleted = false;
            try (Cursor columns = db.rawQuery("PRAGMA table_info(record_schema_guard)", null)) {
                while (columns.moveToNext()) if ("deleted".equals(columns.getString(1))) { hasDeleted = true; break; }
            }
            if (!hasDeleted) {
                db.execSQL("ALTER TABLE record_schema_guard ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
                // Existing tombstones and orphaned guard rows belong to a deleted identity, not
                // a future record that happens to reuse the same ID. Keep ALTER and backfill atomic.
                db.execSQL("UPDATE record_schema_guard SET deleted=COALESCE((SELECT r.deleted FROM records r WHERE r.record_id=record_schema_guard.record_id),1)");
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS record_schema_guard_on_hard_delete "
                    + "AFTER DELETE ON records BEGIN "
                    + "UPDATE record_schema_guard SET deleted=1 WHERE record_id=OLD.record_id; END");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Payloads are additive across compatible app versions: omitted keys are retained and an
     * explicit org.json.JSONObject.NULL replaces a guarded value. A tombstone ends the previous
     * record identity, so reusing its ID starts with the incoming payload only.
     */
    private JSONObject mergeWithGuard(SQLiteDatabase db, String recordId, JSONObject incoming, boolean incomingDeleted) {
        JSONObject merged = new JSONObject();
        try (Cursor guard = db.rawQuery("SELECT payload,deleted FROM record_schema_guard WHERE record_id=?", new String[]{recordId})) {
            if (guard.moveToFirst() && (guard.getInt(1) == 0 || incomingDeleted))
                merged = new JSONObject(guard.getString(0));
        } catch (JSONException malformed) {
            throw new IllegalStateException("Stored AutoSync schema guard is invalid", malformed);
        }
        try {
            return mergePayloadObjects(merged, incoming);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Invalid record field", malformed);
        }
    }

    /** Preserve unknown fields inside nested objects as well as at the top level. */
    private static JSONObject mergePayloadObjects(JSONObject existing, JSONObject incoming) throws JSONException {
        JSONObject merged = new JSONObject(existing.toString());
        Iterator<String> keys = incoming.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = incoming.get(key);
            Object prior = merged.opt(key);
            if (value instanceof JSONObject && prior instanceof JSONObject)
                value = mergePayloadObjects((JSONObject) prior, (JSONObject) value);
            merged.put(key, value);
        }
        return merged;
    }

    private void upsertSchemaGuard(SQLiteDatabase db, String recordId, JSONObject payload, boolean deleted) {
        db.execSQL("INSERT INTO record_schema_guard(record_id,payload,deleted) VALUES(?,?,?) "
                + "ON CONFLICT(record_id) DO UPDATE SET payload=excluded.payload,deleted=excluded.deleted",
                new Object[]{recordId, payload.toString(), deleted ? 1 : 0});
    }

    /** Restore additive fields removed by an older app and repair unsent legacy task writes. */
    private void restoreGuardedPayloads(SQLiteDatabase db) {
        ArrayList<String[]> rows = new ArrayList<>();
        try (Cursor records = db.rawQuery("SELECT record_id,payload,deleted FROM records", null)) {
            while (records.moveToNext()) rows.add(new String[]{records.getString(0), records.getString(1), Integer.toString(records.getInt(2))});
        }
        for (String[] row : rows) {
            String recordId = row[0];
            boolean deleted = "1".equals(row[2]);
            try {
                JSONObject current = new JSONObject(row[1]);
                JSONObject merged = mergeWithGuard(db, recordId, current, deleted);
                upsertSchemaGuard(db, recordId, merged, deleted);
                if (!current.toString().equals(merged.toString())) {
                    ContentValues restore = new ContentValues(); restore.put("payload", merged.toString());
                    db.update("records", restore, "record_id=?", new String[]{recordId});
                }
                ArrayList<String[]> pending = new ArrayList<>();
                try (Cursor events = db.rawQuery(
                        "SELECT op_id,payload,deleted FROM outbox WHERE record_id=? AND state='pending'", new String[]{recordId})) {
                    while (events.moveToNext()) pending.add(new String[]{events.getString(0), events.getString(1), Integer.toString(events.getInt(2))});
                }
                for (String[] event : pending) {
                    JSONObject eventPayload = mergeWithGuard(db, recordId, new JSONObject(event[1]), "1".equals(event[2]));
                    ContentValues update = new ContentValues(); update.put("payload", eventPayload.toString());
                    db.update("outbox", update, "op_id=? AND state='pending'", new String[]{event[0]});
                }
            } catch (JSONException malformed) {
                throw new IllegalStateException("Stored AutoSync record is invalid", malformed);
            }
        }
    }

    private void queueWrite(SQLiteDatabase db, String rid, JSONObject payload, boolean deleted) {
        ensureNoConflict(db, rid);
        try (Cursor pending = db.rawQuery("SELECT op_id FROM outbox WHERE record_id=? AND state='pending' ORDER BY seq DESC LIMIT 1", new String[]{rid})) {
            if (pending.moveToFirst()) {
                ContentValues update = new ContentValues(); update.put("payload", payload.toString()); update.put("deleted", deleted ? 1 : 0);
                db.update("outbox", update, "op_id=?", new String[]{pending.getString(0)});
                return;
            }
        }
        long expected = 0L;
        try (Cursor current = db.rawQuery("SELECT revision FROM records WHERE record_id=?", new String[]{rid})) { if (current.moveToFirst()) expected = current.getLong(0); }
        ContentValues queued = new ContentValues(); queued.put("op_id", UUID.randomUUID().toString().replace("-", "")); queued.put("record_id", rid);
        queued.put("expected_revision", expected); queued.put("payload", payload.toString()); queued.put("deleted", deleted ? 1 : 0);
        queued.put("state", "pending"); queued.put("created_utc", System.currentTimeMillis());
        db.insertOrThrow("outbox", null, queued);
    }

    public static final class Task {
        public final String id, text, serverText;
        public final boolean done, conflict;
        public final long revision;
        public Task(String id, String text, boolean done, long revision, boolean conflict, String serverText) {
            this.id=id; this.text=text; this.done=done; this.revision=revision; this.conflict=conflict; this.serverText=serverText;
        }
    }

    public static final class Record {
        public final String id;
        public final JSONObject payload;
        public final long revision;
        public final boolean deleted, conflict;
        public Record(String id, JSONObject payload, long revision, boolean deleted, boolean conflict) {
            this.id=id; this.payload=payload; this.revision=revision; this.deleted=deleted; this.conflict=conflict;
        }
    }
}
