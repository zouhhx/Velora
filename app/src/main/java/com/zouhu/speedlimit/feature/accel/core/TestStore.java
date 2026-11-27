package com.zouhu.speedlimit.feature.accel.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试记录的 SQLite 持久化。原始 GNSS 样本、分段结果和汇总都存在本机，
 * 退出应用或重启手机不会丢失。
 */
public final class TestStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "velora_accel.db";
    private static final int DB_VERSION = 3;

    public TestStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tests ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "started_at_ms INTEGER NOT NULL,"
                + "ended_at_ms INTEGER NOT NULL,"
                + "created_at_ms INTEGER NOT NULL,"
                + "duration_ms INTEGER,"
                + "total_g REAL,"
                + "start_speed_kmh REAL NOT NULL,"
                + "end_speed_kmh REAL NOT NULL,"
                + "mode TEXT NOT NULL DEFAULT 'ZERO_100',"
                + "forced_start INTEGER NOT NULL DEFAULT 0,"
                + "assist_enabled INTEGER NOT NULL,"
                + "assist_triggered INTEGER NOT NULL,"
                + "assist_validated INTEGER NOT NULL,"
                + "assist_lead_ms INTEGER NOT NULL,"
                + "assist_rejected INTEGER NOT NULL,"
                + "valid INTEGER NOT NULL,"
                + "quality TEXT NOT NULL,"
                + "reason TEXT,"
                + "gps_rate_hz REAL NOT NULL,"
                + "sample_count INTEGER NOT NULL"
                + ")");

        db.execSQL("CREATE TABLE samples ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "test_id INTEGER NOT NULL,"
                + "seq INTEGER NOT NULL,"
                + "t_ms INTEGER NOT NULL,"
                + "wall_ms INTEGER NOT NULL,"
                + "speed_mps REAL NOT NULL,"
                + "accuracy_m REAL,"
                + "speed_accuracy_mps REAL,"
                + "lat REAL,"
                + "lon REAL,"
                + "FOREIGN KEY(test_id) REFERENCES tests(id) ON DELETE CASCADE"
                + ")");
        db.execSQL("CREATE INDEX idx_samples_test ON samples(test_id, seq)");

        db.execSQL("CREATE TABLE segments ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "test_id INTEGER NOT NULL,"
                + "idx INTEGER NOT NULL,"
                + "lower_kmh REAL NOT NULL,"
                + "upper_kmh REAL NOT NULL,"
                + "duration_ms INTEGER,"
                + "avg_g REAL,"
                + "status TEXT NOT NULL,"
                + "note TEXT,"
                + "FOREIGN KEY(test_id) REFERENCES tests(id) ON DELETE CASCADE"
                + ")");
        db.execSQL("CREATE INDEX idx_segments_test ON segments(test_id, idx)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            ensureColumn(db, "mode", "TEXT NOT NULL DEFAULT 'ZERO_100'");
            ensureColumn(db, "forced_start", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static void ensureColumn(SQLiteDatabase db, String column, String definition) {
        boolean found = false;
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(tests)", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameIndex))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            db.execSQL("ALTER TABLE tests ADD COLUMN " + column + " " + definition);
        }
    }

    public long save(TestRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("started_at_ms", record.startedAtMs);
            cv.put("ended_at_ms", record.endedAtMs);
            cv.put("created_at_ms", record.createdAtMs > 0
                    ? record.createdAtMs : System.currentTimeMillis());
            if (record.durationMs > 0L) {
                cv.put("duration_ms", record.durationMs);
            } else {
                cv.putNull("duration_ms");
            }
            if (Double.isFinite(record.totalG)) {
                cv.put("total_g", record.totalG);
            } else {
                cv.putNull("total_g");
            }
            cv.put("start_speed_kmh", record.startSpeedKmh);
            cv.put("end_speed_kmh", record.endSpeedKmh);
            cv.put("mode", record.mode == null ? TestMode.ZERO_100.name() : record.mode.name());
            cv.put("forced_start", record.forcedStart ? 1 : 0);
            cv.put("assist_enabled", record.assistEnabled ? 1 : 0);
            cv.put("assist_triggered", record.assistTriggered ? 1 : 0);
            cv.put("assist_validated", record.assistValidated ? 1 : 0);
            cv.put("assist_lead_ms", record.assistLeadMs);
            cv.put("assist_rejected", record.assistRejected);
            cv.put("valid", record.valid ? 1 : 0);
            cv.put("quality", record.quality);
            cv.put("reason", record.reason == null ? "" : record.reason);
            cv.put("gps_rate_hz", record.gpsRateHz);
            cv.put("sample_count", record.sampleCount);

            long id = db.insertOrThrow("tests", null, cv);
            record.id = id;

            int seq = 0;
            for (TestSample sample : record.samples) {
                ContentValues sv = new ContentValues();
                sv.put("test_id", id);
                sv.put("seq", seq++);
                sv.put("t_ms", sample.tMs);
                sv.put("wall_ms", sample.wallMs);
                sv.put("speed_mps", sample.speedMps);
                if (Double.isFinite(sample.accuracyM)) {
                    sv.put("accuracy_m", sample.accuracyM);
                } else {
                    sv.putNull("accuracy_m");
                }
                if (Double.isFinite(sample.speedAccuracyMps)) {
                    sv.put("speed_accuracy_mps", sample.speedAccuracyMps);
                } else {
                    sv.putNull("speed_accuracy_mps");
                }
                if (Double.isFinite(sample.latitude)) {
                    sv.put("lat", sample.latitude);
                } else {
                    sv.putNull("lat");
                }
                if (Double.isFinite(sample.longitude)) {
                    sv.put("lon", sample.longitude);
                } else {
                    sv.putNull("lon");
                }
                db.insertOrThrow("samples", null, sv);
            }

            for (SegmentResult segment : record.segments) {
                ContentValues gv = new ContentValues();
                gv.put("test_id", id);
                gv.put("idx", segment.index);
                gv.put("lower_kmh", segment.lowerKmh);
                gv.put("upper_kmh", segment.upperKmh);
                if (segment.durationMs != null) {
                    gv.put("duration_ms", segment.durationMs);
                } else {
                    gv.putNull("duration_ms");
                }
                if (segment.avgG != null && Double.isFinite(segment.avgG)) {
                    gv.put("avg_g", segment.avgG);
                } else {
                    gv.putNull("avg_g");
                }
                gv.put("status", segment.status);
                gv.put("note", segment.note == null ? "" : segment.note);
                db.insertOrThrow("segments", null, gv);
            }

            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public List<TestRecord> loadAll() {
        List<TestRecord> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                "tests", null, null, null, null, null,
                "started_at_ms DESC, id DESC")) {
            while (cursor.moveToNext()) {
                result.add(readTestRow(cursor));
            }
        }
        return result;
    }

    public TestRecord load(long id) {
        SQLiteDatabase db = getReadableDatabase();
        TestRecord record = null;
        try (Cursor cursor = db.query(
                "tests", null, "id=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                record = readTestRow(cursor);
            }
        }
        if (record == null) return null;

        try (Cursor cursor = db.query(
                "samples", null, "test_id=?", new String[]{String.valueOf(id)},
                null, null, "seq ASC")) {
            while (cursor.moveToNext()) {
                record.samples.add(new TestSample(
                        cursor.getLong(cursor.getColumnIndexOrThrow("t_ms")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("wall_ms")),
                        cursor.getDouble(cursor.getColumnIndexOrThrow("speed_mps")),
                        getDouble(cursor, "accuracy_m"),
                        getDouble(cursor, "speed_accuracy_mps"),
                        getDouble(cursor, "lat"),
                        getDouble(cursor, "lon")
                ));
            }
        }

        try (Cursor cursor = db.query(
                "segments", null, "test_id=?", new String[]{String.valueOf(id)},
                null, null, "idx ASC")) {
            while (cursor.moveToNext()) {
                int index = cursor.getInt(cursor.getColumnIndexOrThrow("idx"));
                double lower = cursor.getDouble(cursor.getColumnIndexOrThrow("lower_kmh"));
                double upper = cursor.getDouble(cursor.getColumnIndexOrThrow("upper_kmh"));
                Long duration = cursor.isNull(cursor.getColumnIndexOrThrow("duration_ms"))
                        ? null : cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms"));
                Double avgG = cursor.isNull(cursor.getColumnIndexOrThrow("avg_g"))
                        ? null : cursor.getDouble(cursor.getColumnIndexOrThrow("avg_g"));
                String status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
                String note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
                record.segments.add(new SegmentResult(
                        index, lower, upper, duration, avgG, status, note
                ));
            }
        }
        return record;
    }

    public List<TestRecord> loadByIds(long[] ids) {
        List<TestRecord> result = new ArrayList<>();
        if (ids == null) return result;
        for (long id : ids) {
            TestRecord record = load(id);
            if (record != null) result.add(record);
        }
        return result;
    }

    public TestRecord lastValidBefore(TestMode mode, long beforeMs) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                "tests",
                new String[]{"duration_ms", "quality", "gps_rate_hz", "started_at_ms"},
                "valid=1 AND mode=? AND started_at_ms<? AND duration_ms IS NOT NULL AND duration_ms>0",
                new String[]{mode.name(), String.valueOf(beforeMs)},
                null, null, "started_at_ms DESC, id DESC", "1")) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null;
            TestRecord record = new TestRecord();
            record.mode = mode;
            record.valid = true;
            record.durationMs = cursor.getLong(0);
            record.quality = cursor.getString(1);
            record.gpsRateHz = cursor.getDouble(2);
            record.startedAtMs = cursor.getLong(3);
            return record;
        }
    }

    /** Fastest valid run for this mode; used by the compact performance-test UI. */
    public TestRecord bestValid(TestMode mode) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(
                "tests",
                new String[]{"duration_ms", "quality", "gps_rate_hz", "started_at_ms"},
                "valid=1 AND mode=? AND duration_ms IS NOT NULL AND duration_ms>0",
                new String[]{mode.name()},
                null, null, "duration_ms ASC, started_at_ms DESC", "1")) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null;
            TestRecord record = new TestRecord();
            record.mode = mode;
            record.valid = true;
            record.durationMs = cursor.getLong(0);
            record.quality = cursor.getString(1);
            record.gpsRateHz = cursor.getDouble(2);
            record.startedAtMs = cursor.getLong(3);
            return record;
        }
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("samples", "test_id=?", new String[]{String.valueOf(id)});
            db.delete("segments", "test_id=?", new String[]{String.valueOf(id)});
            db.delete("tests", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("samples", null, null);
            db.delete("segments", null, null);
            db.delete("tests", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM tests", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private TestRecord readTestRow(Cursor cursor) {
        TestRecord record = new TestRecord();
        record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        record.startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms"));
        record.endedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("ended_at_ms"));
        record.createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms"));
        record.durationMs = cursor.isNull(cursor.getColumnIndexOrThrow("duration_ms"))
                ? -1L : cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms"));
        record.totalG = cursor.isNull(cursor.getColumnIndexOrThrow("total_g"))
                ? Double.NaN : cursor.getDouble(cursor.getColumnIndexOrThrow("total_g"));
        record.startSpeedKmh = cursor.getDouble(cursor.getColumnIndexOrThrow("start_speed_kmh"));
        record.endSpeedKmh = cursor.getDouble(cursor.getColumnIndexOrThrow("end_speed_kmh"));
        record.mode = TestMode.fromName(cursor.getString(cursor.getColumnIndexOrThrow("mode")));
        record.forcedStart = cursor.getInt(cursor.getColumnIndexOrThrow("forced_start")) != 0;
        record.assistEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("assist_enabled")) != 0;
        record.assistTriggered = cursor.getInt(cursor.getColumnIndexOrThrow("assist_triggered")) != 0;
        record.assistValidated = cursor.getInt(cursor.getColumnIndexOrThrow("assist_validated")) != 0;
        record.assistLeadMs = cursor.getLong(cursor.getColumnIndexOrThrow("assist_lead_ms"));
        record.assistRejected = cursor.getInt(cursor.getColumnIndexOrThrow("assist_rejected"));
        record.valid = cursor.getInt(cursor.getColumnIndexOrThrow("valid")) != 0;
        record.quality = cursor.getString(cursor.getColumnIndexOrThrow("quality"));
        record.reason = cursor.getString(cursor.getColumnIndexOrThrow("reason"));
        record.gpsRateHz = cursor.getDouble(cursor.getColumnIndexOrThrow("gps_rate_hz"));
        record.sampleCount = cursor.getInt(cursor.getColumnIndexOrThrow("sample_count"));
        return record;
    }

    private static double getDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? Double.NaN : cursor.getDouble(index);
    }
}
