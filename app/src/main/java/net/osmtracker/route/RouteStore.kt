package net.osmtracker.route

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RoutePoint(val lat: Double,val lon: Double,val ele: Double,val time: Long,val speed: Float,
    val accuracy: Float = -1f,val bearing: Float = -1f,val satellites: Int = -1,val provider: String = "fused")
data class RouteSummary(val id: Long,val startedAt: Long,val endedAt: Long,val distance: Double,val points: Int)

class RouteStore(context: Context) : SQLiteOpenHelper(context,"route_recorder.db",null,2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trips (_id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL DEFAULT 0, distance REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE points (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, ele REAL NOT NULL, time INTEGER NOT NULL, speed REAL NOT NULL, accuracy REAL NOT NULL DEFAULT -1, bearing REAL NOT NULL DEFAULT -1, satellites INTEGER NOT NULL DEFAULT -1, provider TEXT NOT NULL DEFAULT 'fused')")
        db.execSQL("CREATE INDEX points_trip_idx ON points(trip_id,_id)")
        createDiagnosticsTables(db)
    }
    private fun createDiagnosticsTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gnss_events (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, time INTEGER NOT NULL, satellites_visible INTEGER NOT NULL, satellites_used INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS time_events (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, time INTEGER NOT NULL, event TEXT NOT NULL, detail TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN accuracy REAL NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE points ADD COLUMN bearing REAL NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE points ADD COLUMN satellites INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE points ADD COLUMN provider TEXT NOT NULL DEFAULT 'legacy'")
            createDiagnosticsTables(db)
        }
    }
    @Synchronized fun startTrip(): Long {
        val v=ContentValues().apply { put("started_at",System.currentTimeMillis()) }
        return writableDatabase.insertOrThrow("trips",null,v)
    }
    @Synchronized fun activeTripId(): Long? {
        readableDatabase.rawQuery("SELECT _id FROM trips WHERE ended_at=0 ORDER BY _id DESC LIMIT 1",null).use {
            return if(it.moveToFirst()) it.getLong(0) else null
        }
    }
    @Synchronized fun lastPoint(id: Long): RoutePoint? {
        readableDatabase.query("points",arrayOf("lat","lon","ele","time","speed","accuracy","bearing","satellites","provider"),
            "trip_id=?",arrayOf(id.toString()),null,null,"_id DESC","1").use { c ->
            if(!c.moveToFirst()) return null
            return RoutePoint(c.getDouble(0),c.getDouble(1),if(c.getDouble(2)==-9999.0) Double.NaN else c.getDouble(2),
                c.getLong(3),c.getFloat(4),c.getFloat(5),c.getFloat(6),c.getInt(7),c.getString(8))
        }
    }
    @Synchronized fun addPoint(id: Long,p: RoutePoint,distance: Double) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val v=ContentValues().apply {
                put("trip_id",id); put("lat",p.lat); put("lon",p.lon)
                put("ele",if(p.ele.isFinite()) p.ele else -9999.0); put("time",p.time); put("speed",p.speed)
                put("accuracy",p.accuracy); put("bearing",p.bearing); put("satellites",p.satellites); put("provider",p.provider)
            }
            db.insertOrThrow("points",null,v)
            db.execSQL("UPDATE trips SET distance=? WHERE _id=?",arrayOf(distance,id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun recordGnss(id: Long,visible: Int,used: Int) {
        writableDatabase.execSQL("INSERT INTO gnss_events(trip_id,time,satellites_visible,satellites_used) VALUES(?,?,?,?)",
            arrayOf(id,System.currentTimeMillis(),visible,used))
    }
    @Synchronized fun recordEvent(id: Long,event: String,detail: String?=null) {
        writableDatabase.execSQL("INSERT INTO time_events(trip_id,time,event,detail) VALUES(?,?,?,?)",
            arrayOf(id,System.currentTimeMillis(),event,detail))
    }
    @Synchronized fun finishTrip(id: Long) {
        val v=ContentValues().apply { put("ended_at",System.currentTimeMillis()) }
        writableDatabase.update("trips",v,"_id=?",arrayOf(id.toString()))
    }
    fun points(id: Long): List<RoutePoint> {
        val out=mutableListOf<RoutePoint>()
        readableDatabase.query("points",arrayOf("lat","lon","ele","time","speed","accuracy","bearing","satellites","provider"),
            "trip_id=?",arrayOf(id.toString()),null,null,"_id ASC").use { c ->
            while(c.moveToNext()) out.add(RoutePoint(c.getDouble(0),c.getDouble(1),
                if(c.getDouble(2)==-9999.0) Double.NaN else c.getDouble(2),c.getLong(3),c.getFloat(4),
                c.getFloat(5),c.getFloat(6),c.getInt(7),c.getString(8)))
        }
        return out
    }
    @Synchronized fun deleteTrip(id: Long) {
        val db=writableDatabase; db.beginTransaction()
        try {
            db.delete("points","trip_id=?",arrayOf(id.toString()))
            db.delete("gnss_events","trip_id=?",arrayOf(id.toString()))
            db.delete("time_events","trip_id=?",arrayOf(id.toString()))
            db.delete("trips","_id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun trips(): List<RouteSummary> {
        val out=mutableListOf<RouteSummary>()
        readableDatabase.rawQuery("SELECT t._id,t.started_at,t.ended_at,t.distance,COUNT(p._id) FROM trips t LEFT JOIN points p ON p.trip_id=t._id GROUP BY t._id ORDER BY t._id DESC",null).use { c ->
            while(c.moveToNext()) out.add(RouteSummary(c.getLong(0),c.getLong(1),c.getLong(2),c.getDouble(3),c.getInt(4)))
        }
        return out
    }
}
