package com.hopon.car

import android.content.Context
import android.graphics.*
import android.view.View
import com.hopon.car.map.MapTileStore
import com.hopon.car.map.RoadGeometry
import com.hopon.car.ui.Palette
import com.hopon.car.ui.PickupPresentation
import org.json.JSONObject
import kotlin.math.*

/** OSM base map, shared Naver road geometry and independent stop markers. */
class JourneyMapView(context:Context,private var snapshot:JSONObject,private val tiles:MapTileStore,private val mapStatus:(Boolean)->Unit):View(context){
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoomDelta=0
    private var lastLoaded:Boolean?=null
    private val density=resources.displayMetrics.density
    init{contentDescription=context.getString(R.string.v3_map_description)}
    fun update(value:JSONObject){snapshot=value;invalidate()}
    fun zoom(delta:Int){zoomDelta=(zoomDelta+delta).coerceIn(-3,3);invalidate()}
    fun fit(){zoomDelta=0;invalidate()}
    private fun project(lat:Double,lng:Double):Pair<Double,Double>{
        val sine=sin(Math.toRadians(lat.coerceIn(-85.0,85.0)))
        return ((lng+180)/360) to (.5-ln((1+sine)/(1-sine))/(4*PI))
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);canvas.drawColor(Color.rgb(238,240,229))
        val route=RoadGeometry.path(snapshot)
        val arrival=snapshot.optJSONObject("room")?.optString("status") in listOf("arrived","closed")
        val parked=snapshot.optJSONObject("parking")?.optJSONObject("position")
        val arrivalPosition=parked?:snapshot.optJSONObject("telemetry")?:snapshot.optJSONObject("room")?.optJSONObject("destination")
        val markers=if(arrival && arrivalPosition!=null)listOf(arrivalPosition.getDouble("lat") to arrivalPosition.getDouble("lng")) else RoadGeometry.stops(snapshot)
        val geo=if(arrival)markers else route+markers
        if(geo.isEmpty())return
        val coords=geo.map{project(it.first,it.second)}
        val minX=coords.minOf{it.first};val maxX=coords.maxOf{it.first};val minY=coords.minOf{it.second};val maxY=coords.maxOf{it.second}
        val tileSize=256f*density*1.65f
        var fitZoom=3
        for(z in 17 downTo 3){val world=tileSize*2.0.pow(z);if((maxX-minX)*world<width*.77 && (maxY-minY)*world<height*.45){fitZoom=z;break}}
        val zoom=(fitZoom+zoomDelta).coerceIn(3,18);val count=1 shl zoom;val world=tileSize*count
        val cx=(minX+maxX)/2;val cy=(minY+maxY)/2
        val originX=cx*world-width/2;val originY=cy*world-height*.56
        var loaded=0
        if(tiles.enabled){
            for(x in floor(originX/tileSize).toInt()..floor((originX+width)/tileSize).toInt()){
                for(y in floor(originY/tileSize).toInt()..floor((originY+height)/tileSize).toInt()){
                    if(y !in 0 until count)continue
                    val wrapped=((x%count)+count)%count;val key="$zoom-$wrapped-$y";val bitmap=tiles.peek(key)
                    if(bitmap!=null){pen.color=Color.WHITE;pen.alpha=235;canvas.drawBitmap(bitmap,null,RectF((x*tileSize-originX).toFloat(),(y*tileSize-originY).toFloat(),((x+1)*tileSize-originX).toFloat(),((y+1)*tileSize-originY).toFloat()),pen);loaded++;pen.alpha=255}
                    if(isAttachedToWindow)tiles.request(zoom,wrapped,y){if(isAttachedToWindow)invalidate()}
                }
            }
        }
        if(lastLoaded!=(loaded>0)){lastLoaded=loaded>0;post{mapStatus(loaded>0)}}
        fun point(pair:Pair<Double,Double>)=PointF((pair.first*world-originX).toFloat(),(pair.second*world-originY).toFloat())
        val path=Path();(if(arrival)emptyList() else route.map{project(it.first,it.second)}).forEachIndexed{i,p->val v=point(p);if(i==0)path.moveTo(v.x,v.y)else path.lineTo(v.x,v.y)}
        pen.style=Paint.Style.STROKE;pen.strokeWidth=9*density;pen.strokeJoin=Paint.Join.ROUND;pen.strokeCap=Paint.Cap.ROUND;pen.color=Color.WHITE;canvas.drawPath(path,pen)
        pen.strokeWidth=5*density;pen.color=Palette.forest;pen.pathEffect=null;canvas.drawPath(path,pen)
        pen.pathEffect=null;pen.style=Paint.Style.FILL
        val pickupPhase=snapshot.optJSONObject("room")?.optString("status")=="pickup"
        val pickupStops=if(pickupPhase)PickupPresentation.remaining(snapshot) else emptyList()
        markers.map{project(it.first,it.second)}.forEachIndexed{i,p->
            val v=point(p);val radius=(if(i==0)23 else 18)*density
            pen.color=Color.WHITE;canvas.drawCircle(v.x,v.y,radius+4*density,pen)
            pen.color=if(i==markers.lastIndex)Palette.forest else Palette.mint;canvas.drawCircle(v.x,v.y,radius,pen)
            pen.textSize=20*density;pen.textAlign=Paint.Align.CENTER;pen.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);pen.color=Color.WHITE
            val text=if(arrival && parked!=null)"P" else if(i==0)"↑" else if(i==markers.lastIndex)"◎" else if(pickupPhase && i<=pickupStops.size)pickupStops[i-1].number.toString() else if(pickupPhase)"경${i-pickupStops.size}" else i.toString()
            canvas.drawText(text,v.x,v.y-(pen.ascent()+pen.descent())/2,pen)
        }
        // Pickup marker numbers match the full pickup list, including completed stops; course stops use 경N.
    }
}
