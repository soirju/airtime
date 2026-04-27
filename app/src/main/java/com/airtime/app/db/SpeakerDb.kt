package com.airtime.app.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.airtime.app.audio.SpeakerIdentifier
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persists speaker profiles (name + voice embedding) across app restarts.
 * Talk-time data is intentionally NOT persisted.
 */
class SpeakerDb(context: Context) : SQLiteOpenHelper(context, "speakers.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE speakers (
                id INTEGER PRIMARY KEY,
                name TEXT,
                embedding BLOB NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS speakers")
        onCreate(db)
    }

    fun saveSpeaker(profile: SpeakerIdentifier.SpeakerProfile) {
        val cv = ContentValues().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("embedding", toBlob(profile.embedding))
        }
        writableDatabase.insertWithOnConflict("speakers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSpeaker(id: Int) {
        writableDatabase.delete("speakers", "id = ?", arrayOf(id.toString()))
    }

    fun loadAll(): List<SpeakerIdentifier.SpeakerProfile> {
        val list = mutableListOf<SpeakerIdentifier.SpeakerProfile>()
        readableDatabase.rawQuery("SELECT id, name, embedding FROM speakers", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SpeakerIdentifier.SpeakerProfile(
                        id = c.getInt(0),
                        name = c.getString(1),
                        embedding = fromBlob(c.getBlob(2))
                    )
                )
            }
        }
        return list
    }

    private fun toBlob(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun fromBlob(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.float }
    }
}
