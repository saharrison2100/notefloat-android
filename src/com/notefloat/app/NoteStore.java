package com.notefloat.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class NoteStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "notefloat.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "notes";

    private static NoteStore instance;

    public static synchronized NoteStore get(Context context) {
        if (instance == null) {
            instance = new NoteStore(context.getApplicationContext());
        }
        return instance;
    }

    private NoteStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL DEFAULT '', "
                + "body TEXT NOT NULL DEFAULT '', "
                + "pinned INTEGER NOT NULL DEFAULT 0, "
                + "created INTEGER NOT NULL, "
                + "updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_notes_updated ON " + TABLE + " (updated)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // first release: nothing to migrate yet
    }

    public long insert(String body) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("title", Note.firstLine(body));
        v.put("body", body);
        v.put("pinned", 0);
        v.put("created", now);
        v.put("updated", now);
        return getWritableDatabase().insert(TABLE, null, v);
    }

    public void update(long id, String body) {
        ContentValues v = new ContentValues();
        v.put("title", Note.firstLine(body));
        v.put("body", body);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "_id = ?", new String[]{String.valueOf(id)});
    }

    public void setPinned(long id, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update(TABLE, v, "_id = ?", new String[]{String.valueOf(id)});
    }

    public Note get(long id) {
        Cursor c = getReadableDatabase().query(TABLE, null, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        Note note = null;
        if (c != null) {
            if (c.moveToFirst()) {
                note = read(c);
            }
            c.close();
        }
        return note;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        int total = 0;
        if (c != null) {
            if (c.moveToFirst()) {
                total = c.getInt(0);
            }
            c.close();
        }
        return total;
    }

    public List<Note> list(String query) {
        List<Note> out = new ArrayList<Note>();
        String order = "pinned DESC, updated DESC";
        Cursor c;
        if (query == null || query.trim().length() == 0) {
            c = getReadableDatabase().query(TABLE, null, null, null, null, null, order);
        } else {
            String like = "%" + query.trim() + "%";
            c = getReadableDatabase().query(TABLE, null,
                    "(title LIKE ? OR body LIKE ?)", new String[]{like, like},
                    null, null, order);
        }
        if (c != null) {
            while (c.moveToNext()) {
                out.add(read(c));
            }
            c.close();
        }
        return out;
    }

    private Note read(Cursor c) {
        Note n = new Note();
        n.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        n.title = c.getString(c.getColumnIndexOrThrow("title"));
        n.body = c.getString(c.getColumnIndexOrThrow("body"));
        n.pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1;
        n.created = c.getLong(c.getColumnIndexOrThrow("created"));
        n.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return n;
    }
}
