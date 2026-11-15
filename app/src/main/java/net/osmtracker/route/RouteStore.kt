package net.osmtracker.route
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
data class RoutePoint(val lat: Double,val lon: Double,val ele: Double,val time: Long,val speed: Float)
data class RouteSummary(val id: Long,val startedAt: Long,val endedAt: Long,val distance: Double,val points: Int)
class RouteStore(context: Context): SQLiteOpenHelper(context,"route_recorder.db",null,1) {
 override fun onCreate(db: SQLiteDatabase) {
  db.execSQL("CREATE TABLE trips (_id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL DEFAULT 0, distance REAL NOT NULL DEFAULT 0)")
  db.execSQL("CREATE TABLE points (_id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, ele REAL NOT NULL, time INTEGER NOT NULL, speed REAL NOT NULL)")
  db.execSQL("CREATE INDEX points_trip_idx ON points(trip_id,_id)")
 }
 override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {}
 @Synchronized fun startTrip(): Long { val v=ContentValues().apply { put("started_at",System.currentTimeMillis()) }; return writableDatabase.insert("trips",null,v) }
 @Synchronized fun addPoint(id: Long,p: RoutePoint,distance: Double) {
  val v=ContentValues().apply { put("trip_id",id);put("lat",p.lat);put("lon",p.lon);put("ele",p.ele);put("time",p.time);put("speed",p.speed) }
  writableDatabase.insert("points",null,v); writableDatabase.execSQL("UPDATE trips SET distance=? WHERE _id=?",arrayOf(distance,id))
 }
 @Synchronized fun finishTrip(id: Long) { val v=ContentValues().apply { put("ended_at",System.currentTimeMillis()) }; writableDatabase.update("trips",v,"_id=?",arrayOf(id.toString())) }
 fun points(id: Long): List<RoutePoint> { val out=mutableListOf<RoutePoint>(); readableDatabase.query("points",arrayOf("lat","lon","ele","time","speed"),"trip_id=?",arrayOf(id.toString()),null,null,"_id ASC").use { c -> while(c.moveToNext()) out.add(RoutePoint(c.getDouble(0),c.getDouble(1),c.getDouble(2),c.getLong(3),c.getFloat(4))) }; return out }
 @Synchronized fun deleteTrip(id: Long) {
  writableDatabase.beginTransaction()
  try {
   writableDatabase.delete("points","trip_id=?",arrayOf(id.toString()))
   writableDatabase.delete("trips","_id=?",arrayOf(id.toString()))
   writableDatabase.setTransactionSuccessful()
  } finally { writableDatabase.endTransaction() }
 }
 fun trips(): List<RouteSummary> { val out=mutableListOf<RouteSummary>(); readableDatabase.rawQuery("SELECT t._id,t.started_at,t.ended_at,t.distance,COUNT(p._id) FROM trips t LEFT JOIN points p ON p.trip_id=t._id GROUP BY t._id ORDER BY t._id DESC",null).use { c -> while(c.moveToNext()) out.add(RouteSummary(c.getLong(0),c.getLong(1),c.getLong(2),c.getDouble(3),c.getInt(4))) }; return out }
}
