package com.hopon.car

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONArray

/** Deliberately illustrative map: no tiles, tracking, or real route geometry. */
class LegacyJourneyMapView(context:Context,private val members:JSONArray):View(context){
 private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
 init{contentDescription="샘플 지도. 실제 지도 또는 길 안내가 아닙니다. 픽업 지점과 서울숲 목적지의 예시 배치."}
 private fun color(hex:String){pen.color=Color.parseColor(hex)}
 override fun onDraw(canvas:Canvas){
  super.onDraw(canvas)
  val sx=width/1000f;val sy=height/900f
  canvas.save();canvas.scale(sx,sy)
  canvas.drawColor(Color.parseColor("#DBE4E5"))
  color("#BDD7C8");canvas.drawRoundRect(540f,150f,980f,380f,36f,36f,pen);canvas.drawRoundRect(30f,410f,270f,730f,40f,40f,pen)
  val river=Path().apply{moveTo(-60f,790f);cubicTo(330f,560f,610f,910f,1080f,620f)}
  pen.style=Paint.Style.STROKE;pen.strokeWidth=110f;color("#AAD8E0");canvas.drawPath(river,pen)
  pen.strokeWidth=16f;color("#F7FAF9")
  for(i in 0..8){canvas.drawLine(i*150f-180f,0f,i*150f+150f,900f,pen)}
  for(i in 0..6){canvas.drawLine(0f,i*145f+90f,1000f,i*145f-120f,pen)}
  pen.strokeWidth=30f;canvas.drawLine(-30f,580f,1030f,120f,pen)
  color("#859AA2");pen.strokeWidth=2f;canvas.drawLine(-30f,580f,1030f,120f,pen)
  val route=Path().apply{moveTo(200f,450f);lineTo(360f,380f);lineTo(490f,420f);lineTo(690f,290f);lineTo(795f,265f)}
  color("#FFFFFF");pen.strokeWidth=17f;pen.strokeCap=Paint.Cap.ROUND;pen.strokeJoin=Paint.Join.ROUND;canvas.drawPath(route,pen)
  color("#187D8E");pen.strokeWidth=9f;canvas.drawPath(route,pen);pen.style=Paint.Style.FILL
  pen.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);pen.textSize=24f;color("#568476");canvas.drawText("서울숲",780f,205f,pen)
  val markers=listOf(360f to 380f,490f to 420f,590f to 355f,690f to 290f)
  for(i in 0 until members.length().coerceAtMost(4)){
   val (x,y)=markers[i];color("#FFFFFF");canvas.drawCircle(x,y,28f,pen);color("#185F79");canvas.drawCircle(x,y,23f,pen);color("#FFFFFF");pen.textSize=23f;pen.textAlign=Paint.Align.CENTER;canvas.drawText("${i+1}",x,y+8f,pen)
   color("#182D37");pen.textSize=20f;canvas.drawText(members.getJSONObject(i).optString("displayName"),x,y-39f,pen)
  }
  color("#FFFFFF");canvas.drawCircle(795f,265f,29f,pen);color("#DB795D");canvas.drawCircle(795f,265f,22f,pen);color("#FFFFFF");pen.textSize=20f;canvas.drawText("도착",795f,272f,pen)
  color("#FFFFFF");canvas.drawCircle(200f,450f,30f,pen);color("#187D8E");canvas.drawCircle(200f,450f,24f,pen);color("#FFFFFF")
  val arrow=Path().apply{moveTo(200f,433f);lineTo(187f,464f);lineTo(200f,457f);lineTo(213f,464f);close()};canvas.drawPath(arrow,pen)
  pen.textAlign=Paint.Align.LEFT;canvas.restore()
 }
}
