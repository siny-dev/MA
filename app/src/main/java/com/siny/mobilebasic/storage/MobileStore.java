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
        ContentValues pending=new ContentValues(); pending.put("state", "pending");
        db.update("outbox", pending, "state='sending'", null);
    }

    public synchronized String addTask(String text) { return saveTask("r"+UUID.randomUUID().toString().replace("-", ""), text, false); }

    public synchronized String saveTask(String recordId, String text, boolean done) {
        String title = text == null ? "" : text.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("작업 내용을 입력하세요.");
        if (hasConflict(recordId)) throw new IllegalStateException("먼저 동기화 충돌을 해결하세요.");
        JSONObject payload = new JSONObject();
        try { payload.put("text", title); payload.put("done", done); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        writeLocal(recordId, payload, false);
        queueWrite(recordId, payload, false);
        return recordId;
    }

    public synchronized void deleteTask(String recordId) {
        if (hasConflict(recordId)) throw new IllegalStateException("먼저 동기화 충돌을 해결하세요.");
        JSONObject payload = localPayload(recordId);
        if (payload == null) return;
        writeLocal(recordId, payload, true);
        queueWrite(recordId, payload, true);
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
            ContentValues resolution = new ContentValues(); resolution.put("state", "resolved");
            db.update("outbox", resolution, "record_id=? AND state='conflict'", new String[]{recordId});
            String op = UUID.randomUUID().toString().replace("-", "");
            ContentValues next = new ContentValues(); next.put("op_id", op); next.put("record_id", recordId); next.put("expected_revision", expected);
            next.put("payload", chosen.toString()); next.put("deleted", deleted ? 1 : 0); next.put("state", "pending"); next.put("created_utc", System.currentTimeMillis());
            db.insertOrThrow("outbox", null, next);
            ContentValues updateLocal = new ContentValues(); updateLocal.put("revision", expected); updateLocal.put("payload", chosen.toString()); updateLocal.put("deleted", deleted ? 1 : 0);
            db.update("records", updateLocal, "record_id=?", new String[]{recordId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void applyRemoteRow(SQLiteDatabase db, JSONObject item) throws JSONException {
        String rid = item.getString("record_id");
        try (Cursor pending = db.rawQuery("SELECT 1 FROM outbox WHERE record_id=? AND state IN ('pending','sending','conflict') LIMIT 1", new String[]{rid})) {
            if (pending.moveToFirst()) return;
        }
        ContentValues remote = new ContentValues(); remote.put("record_id", rid); remote.put("revision", item.getLong("revision"));
        remote.put("payload", item.getJSONObject("payload").toString()); remote.put("deleted", item.optBoolean("deleted") ? 1 : 0);
        remote.put("updated_utc", item.optString("updated_utc", ""));
        db.insertWithOnConflict("records", null, remote, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void writeLocal(String rid, JSONObject payload, boolean deleted) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("record_id", rid); values.put("payload", payload.toString());
            values.put("deleted", deleted ? 1 : 0); values.put("updated_utc", Long.toString(System.currentTimeMillis()));
            try (Cursor old = db.rawQuery("SELECT revision FROM records WHERE record_id=?", new String[]{rid})) { values.put("revision", old.moveToFirst() ? old.getLong(0) : 0L); }
            db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private JSONObject localPayload(String rid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM records WHERE record_id=?", new String[]{rid})) {
            if (!c.moveToFirst()) return null;
            return new JSONObject(c.getString(0));
        } catch (JSONException e) { return null; }
    }

    private boolean hasConflict(String rid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM outbox WHERE record_id=? AND state='conflict' LIMIT 1", new String[]{rid})) {
            return c.moveToFirst();
        }
    }

    private void queueWrite(String rid, JSONObject payload, boolean deleted) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            try (Cursor conflict = db.rawQuery("SELECT 1 FROM outbox WHERE record_id=? AND state='conflict' LIMIT 1", new String[]{rid})) {
                if (conflict.moveToFirst()) throw new IllegalStateException("먼저 동기화 충돌을 해결하세요.");
            }
            try (Cursor pending = db.rawQuery("SELECT op_id FROM outbox WHERE record_id=? AND state='pending' ORDER BY seq DESC LIMIT 1", new String[]{rid})) {
                if (pending.moveToFirst()) {
                    ContentValues update = new ContentValues(); update.put("payload", payload.toString()); update.put("deleted", deleted ? 1 : 0);
                    db.update("outbox", update, "op_id=?", new String[]{pending.getString(0)});
                    db.setTransactionSuccessful(); return;
                }
            }
            long expected = 0L;
            try (Cursor current = db.rawQuery("SELECT revision FROM records WHERE record_id=?", new String[]{rid})) { if (current.moveToFirst()) expected = current.getLong(0); }
            ContentValues queued = new ContentValues(); queued.put("op_id", UUID.randomUUID().toString().replace("-", "")); queued.put("record_id", rid);
            queued.put("expected_revision", expected); queued.put("payload", payload.toString()); queued.put("deleted", deleted ? 1 : 0);
            queued.put("state", "pending"); queued.put("created_utc", System.currentTimeMillis());
            db.insertOrThrow("outbox", null, queued);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public static final class Task {
        public final String id, text, serverText;
        public final boolean done, conflict;
        public final long revision;
        public Task(String id, String text, boolean done, long revision, boolean conflict, String serverText) {
            this.id=id; this.text=text; this.done=done; this.revision=revision; this.conflict=conflict; this.serverText=serverText;
        }
    }
}
