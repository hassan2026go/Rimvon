package com.rimvon.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Contact(
    val id: String,
    val name: String,
    val pub: String?,
    val pinned: Boolean,
    val blocked: Boolean,
    val ts: Long,
)

/** status: -1 failed, 0 waiting for network, 1 reached server, 2 delivered, 3 read. */
data class Msg(
    val id: Long,
    val mid: String,
    val peer: String,
    val out: Boolean,
    val kind: String, // text | img | voice | file | sys
    val text: String,
    val path: String?,
    val ts: Long,
    val status: Int,
    val reply: String?,
    val unread: Boolean,
    val dur: Int,
)

data class CallLog(
    val id: Long, val peer: String, val out: Boolean, val ts: Long,
    val dur: Int, val video: Boolean, val missed: Boolean,
)

data class ChatRow(val contact: Contact, val last: Msg?, val unread: Int)

/** Everything is stored only on this phone. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "rimvon.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT NOT NULL, pub TEXT, " +
                "pinned INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0, ts INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, mid TEXT NOT NULL, peer TEXT NOT NULL, " +
                "out INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT, path TEXT, ts INTEGER NOT NULL, " +
                "status INTEGER DEFAULT 0, reply TEXT, unread INTEGER DEFAULT 0, dur INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_mid ON messages(peer, mid)")
        db.execSQL("CREATE INDEX idx_peer_ts ON messages(peer, ts)")
        db.execSQL(
            "CREATE TABLE calls(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, out INTEGER, " +
                "ts INTEGER, dur INTEGER, video INTEGER, missed INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /* ---------- contacts ---------- */
    private fun contactOf(c: Cursor) = Contact(
        id = c.getString(0), name = c.getString(1), pub = c.getString(2),
        pinned = c.getInt(3) == 1, blocked = c.getInt(4) == 1, ts = c.getLong(5),
    )

    fun contact(id: String): Contact? = readableDatabase.rawQuery(
        "SELECT id,name,pub,pinned,blocked,ts FROM contacts WHERE id=?", arrayOf(id)
    ).use { if (it.moveToFirst()) contactOf(it) else null }

    fun contacts(): List<Contact> = readableDatabase.rawQuery(
        "SELECT id,name,pub,pinned,blocked,ts FROM contacts ORDER BY name COLLATE NOCASE", null
    ).use { c -> buildList { while (c.moveToNext()) add(contactOf(c)) } }

    fun upsertContact(c: Contact) {
        val v = ContentValues().apply {
            put("id", c.id); put("name", c.name); put("pub", c.pub)
            put("pinned", if (c.pinned) 1 else 0); put("blocked", if (c.blocked) 1 else 0); put("ts", c.ts)
        }
        writableDatabase.insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun setContact(id: String, col: String, value: Any?) {
        val v = ContentValues()
        when (value) {
            null -> v.putNull(col)
            is String -> v.put(col, value)
            is Long -> v.put(col, value)
            is Int -> v.put(col, value)
            is Boolean -> v.put(col, if (value) 1 else 0)
        }
        writableDatabase.update("contacts", v, "id=?", arrayOf(id))
    }

    fun rename(id: String, name: String) = setContact(id, "name", name)
    fun setPub(id: String, pub: String) = setContact(id, "pub", pub)
    fun setPinned(id: String, on: Boolean) = setContact(id, "pinned", on)
    fun setBlocked(id: String, on: Boolean) = setContact(id, "blocked", on)
    fun touch(id: String, ts: Long) = setContact(id, "ts", ts)
    fun deleteContact(id: String) { writableDatabase.delete("contacts", "id=?", arrayOf(id)) }

    /* ---------- messages ---------- */
    private fun msgOf(c: Cursor) = Msg(
        id = c.getLong(0), mid = c.getString(1), peer = c.getString(2), out = c.getInt(3) == 1,
        kind = c.getString(4), text = c.getString(5) ?: "", path = c.getString(6), ts = c.getLong(7),
        status = c.getInt(8), reply = c.getString(9), unread = c.getInt(10) == 1, dur = c.getInt(11),
    )

    private val msgCols = "id,mid,peer,out,kind,text,path,ts,status,reply,unread,dur"

    /** Returns false if a message with the same (peer, mid) already exists. */
    fun insertMsg(m: Msg): Boolean {
        val v = ContentValues().apply {
            put("mid", m.mid); put("peer", m.peer); put("out", if (m.out) 1 else 0)
            put("kind", m.kind); put("text", m.text); put("path", m.path); put("ts", m.ts)
            put("status", m.status); put("reply", m.reply); put("unread", if (m.unread) 1 else 0)
            put("dur", m.dur)
        }
        return writableDatabase.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun messages(peer: String, limit: Int = 400): List<Msg> = readableDatabase.rawQuery(
        "SELECT $msgCols FROM messages WHERE peer=? ORDER BY ts DESC, id DESC LIMIT $limit", arrayOf(peer)
    ).use { c -> buildList { while (c.moveToNext()) add(msgOf(c)) }.asReversed() }

    fun msgByMid(mid: String): Msg? = readableDatabase.rawQuery(
        "SELECT $msgCols FROM messages WHERE mid=? LIMIT 1", arrayOf(mid)
    ).use { if (it.moveToFirst()) msgOf(it) else null }

    fun msgOfPeer(peer: String, mid: String): Msg? = readableDatabase.rawQuery(
        "SELECT $msgCols FROM messages WHERE peer=? AND mid=?", arrayOf(peer, mid)
    ).use { if (it.moveToFirst()) msgOf(it) else null }

    private fun lastMsg(peer: String): Msg? = readableDatabase.rawQuery(
        "SELECT $msgCols FROM messages WHERE peer=? ORDER BY ts DESC, id DESC LIMIT 1", arrayOf(peer)
    ).use { if (it.moveToFirst()) msgOf(it) else null }

    private fun unreadCount(peer: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE peer=? AND out=0 AND unread=1", arrayOf(peer)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun chats(): List<ChatRow> {
        val rows = contacts().mapNotNull { c ->
            val last = lastMsg(c.id)
            if (last == null && !c.pinned) null else ChatRow(c, last, unreadCount(c.id))
        }
        return rows.sortedWith(
            compareByDescending<ChatRow> { it.contact.pinned }.thenByDescending { it.last?.ts ?: 0L }
        )
    }

    fun totalUnread(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE out=0 AND unread=1", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Status only moves forward (1 -> 2 -> 3). Failed (-1) is set explicitly. */
    fun updateStatus(mid: String, status: Int) {
        writableDatabase.execSQL("UPDATE messages SET status=? WHERE mid=? AND out=1 AND status<?", arrayOf<Any>(status, mid, status))
    }

    fun setFailed(mid: String) {
        writableDatabase.execSQL("UPDATE messages SET status=-1 WHERE mid=? AND out=1 AND status=0", arrayOf<Any>(mid))
    }

    fun retryFailed(mid: String) {
        writableDatabase.execSQL("UPDATE messages SET status=0 WHERE mid=? AND out=1 AND status=-1", arrayOf<Any>(mid))
    }

    /** Marks incoming messages read and returns their ids so read receipts can be sent. */
    fun markRead(peer: String): List<String> {
        val mids = readableDatabase.rawQuery(
            "SELECT mid FROM messages WHERE peer=? AND out=0 AND unread=1", arrayOf(peer)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (mids.isNotEmpty()) {
            writableDatabase.execSQL("UPDATE messages SET unread=0 WHERE peer=? AND out=0 AND unread=1", arrayOf<Any>(peer))
        }
        return mids
    }

    fun pendingOut(): List<Msg> = readableDatabase.rawQuery(
        "SELECT $msgCols FROM messages WHERE out=1 AND status=0 ORDER BY ts", null
    ).use { c -> buildList { while (c.moveToNext()) add(msgOf(c)) } }

    fun deleteMsg(peer: String, mid: String) {
        writableDatabase.delete("messages", "peer=? AND mid=?", arrayOf(peer, mid))
    }

    fun mediaPaths(peer: String?): List<String> = readableDatabase.rawQuery(
        if (peer == null) "SELECT path FROM messages WHERE path IS NOT NULL"
        else "SELECT path FROM messages WHERE path IS NOT NULL AND peer=?",
        if (peer == null) null else arrayOf(peer)
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun deleteChat(peer: String) { writableDatabase.delete("messages", "peer=?", arrayOf(peer)) }

    /* ---------- calls ---------- */
    fun addCall(c: CallLog) {
        val v = ContentValues().apply {
            put("peer", c.peer); put("out", if (c.out) 1 else 0); put("ts", c.ts)
            put("dur", c.dur); put("video", if (c.video) 1 else 0); put("missed", if (c.missed) 1 else 0)
        }
        writableDatabase.insert("calls", null, v)
    }

    fun calls(limit: Int = 100): List<CallLog> = readableDatabase.rawQuery(
        "SELECT id,peer,out,ts,dur,video,missed FROM calls ORDER BY ts DESC LIMIT $limit", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                CallLog(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getLong(3), c.getInt(4),
                    c.getInt(5) == 1, c.getInt(6) == 1)
            )
        }
    }

    fun clearCalls() { writableDatabase.delete("calls", null, null) }

    fun wipe() {
        writableDatabase.apply {
            delete("messages", null, null); delete("contacts", null, null); delete("calls", null, null)
        }
    }
}
