package com.hopon.car.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Visible viewport only. Identified HTTPS requests, conditional refresh, HTTP expiry and 7-day fallback cache.
 * No prefetch, offline download facility, bundled tiles, account tokens or passenger data.
 */
class MapTileStore(context: Context) {
    private val cache=File(context.cacheDir,"osm-visible-tiles").apply{mkdirs()}
    private val memory=object:LruCache<String,Bitmap>(64){}
    private val expires=ConcurrentHashMap<String,Long>()
    private val loading=ConcurrentHashMap.newKeySet<String>()
    private val retryAfter=ConcurrentHashMap<String,Long>()
    private val worker=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    @Volatile private var providerRetry=0L
    @Volatile var lastFailure=""; private set
    var enabled=true
    fun peek(key:String):Bitmap?=memory.get(key)
    fun request(z:Int,x:Int,y:Int,updated:()->Unit){
        val key="$z-$x-$y"
        if(closed||!enabled||(memory.get(key)!=null && System.currentTimeMillis()<(expires[key]?:0L))||System.currentTimeMillis()<providerRetry||System.currentTimeMillis()<(retryAfter[key]?:0)||!loading.add(key))return
        worker.execute {
            try{
                val file=File(cache,"$key.png");val metaFile=File(cache,"$key.json")
                val meta=runCatching{JSONObject(metaFile.readText())}.getOrDefault(JSONObject())
                var bitmap=if(file.exists()) BitmapFactory.decodeFile(file.path) else null
                expires[key]=meta.optLong("expires")
                if(bitmap!=null){memory.put(key,bitmap);main.post{if(!closed)updated()}}
                if(bitmap!=null && meta.optLong("expires")>System.currentTimeMillis())return@execute
                val connection=URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                try{
                    connection.connectTimeout=5000;connection.readTimeout=6000
                    connection.setRequestProperty("User-Agent","MOVOVehicle/0.3.1 (Android; com.hopon.car; local development demo)")
                    if(meta.optString("etag").isNotBlank())connection.setRequestProperty("If-None-Match",meta.getString("etag"))
                    if(meta.optString("modified").isNotBlank())connection.setRequestProperty("If-Modified-Since",meta.getString("modified"))
                    val status=connection.responseCode
                    if(status==429||status==403){lastFailure="Map provider HTTP $status";android.util.Log.w("MOVOMap",lastFailure);providerRetry=System.currentTimeMillis()+60000;return@execute}
                    if(status!=200 && status!=304)throw IllegalStateException("Tile unavailable")
                    if(status==200){
                        val data=connection.inputStream.use { input->
                            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var total=0
                            while(true){val n=input.read(buffer);if(n<0)break;total+=n;check(total<=2_000_000);output.write(buffer,0,n)};output.toByteArray()
                        }
                        bitmap=BitmapFactory.decodeByteArray(data,0,data.size)?:error("Invalid map image")
                        file.writeBytes(data);memory.put(key,bitmap)
                    }
                    val now=System.currentTimeMillis()
                    val maxAge=Regex("max-age=(\\d+)").find(connection.getHeaderField("Cache-Control")?:"")?.groupValues?.get(1)?.toLongOrNull()
                    val age=connection.getHeaderField("Age")?.toLongOrNull()?:0L
                    val expiry=when {
                        maxAge!=null -> now+maxOf(0,maxAge-age)*1000
                        connection.expiration>0 -> connection.expiration
                        else -> now+7*24*60*60*1000L
                    }
                    expires[key]=expiry
                    // Rate-limit even an immediately stale response; never spin on revalidation.
                    retryAfter[key]=now+60000
                    metaFile.writeText(JSONObject().put("expires",expiry)
                        .put("etag",connection.getHeaderField("ETag")?:meta.optString("etag"))
                        .put("modified",connection.getHeaderField("Last-Modified")?:meta.optString("modified")).toString())
                    lastFailure=""
                }finally{connection.disconnect()}
            }catch(e:Exception){lastFailure=e.javaClass.simpleName+": "+(e.message?:"Map unavailable");android.util.Log.w("MOVOMap",lastFailure);retryAfter[key]=System.currentTimeMillis()+60000}
            finally{loading.remove(key);main.post{if(!closed)updated()}}
        }
    }
    fun close(){closed=true;worker.shutdownNow();memory.evictAll()}
}
