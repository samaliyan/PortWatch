package com.samaliyan.portwatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Event(
    val time: Long,
    val serverId: String,
    val serverName: String,
    val target: String,
    val ok: Boolean,
    val detail: String,
)

/** Stores every up/down change. Keeps the newest 5000 rows. */
class HistoryDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "server_id TEXT NOT NULL, server_name TEXT NOT NULL, target TEXT NOT NULL, " +
                "ok INTEGER NOT NULL, detail TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(r: CheckResult) {
        val db = writableDatabase
        db.insert("events", null, ContentValues().apply {
            put("ts", r.time)
            put("server_id", r.serverId)
            put("server_name", r.serverName)
            put("target", r.target.label)
            put("ok", if (r.ok) 1 else 0)
            put("detail", r.detail)
        })
        db.execSQL("DELETE FROM events WHERE _id <= (SELECT MAX(_id) FROM events) - 5000")
    }

    /** Newest first. */
    @Synchronized
    fun list(limit: Int = 2000): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT ts, server_id, server_name, target, ok, detail FROM events ORDER BY ts DESC, _id DESC LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Event(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getString(5))
            }
        }
        return out
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("events", null, null)
    }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(ctx: Context): HistoryDb =
            instance ?: synchronized(this) {
                instance ?: HistoryDb(ctx.applicationContext).also { instance = it }
            }
    }
}
