package com.hopon.car.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.*
import com.hopon.car.R
import org.json.JSONArray
import org.json.JSONObject

/** Five actual focusable seat targets over a top-down cabin, not five dashboard cards. */
class PartySeatsView(context: Context, members: JSONArray, host: String, interactive: Boolean, highlightedMember: String = "", choose: (JSONObject) -> Unit) : FrameLayout(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val tiles=mutableListOf<LinearLayout>()
    private val people=(0 until members.length()).map { members.getJSONObject(it) }
    private val d=Design(context)
    init {
        setWillNotDraw(false)
        for(index in 0..4){
            val m=people.firstOrNull{it.optInt("seat")==index};val driver=index==0
            val rawName=if(driver) host.ifBlank{context.getString(R.string.seat_driver)} else m?.optString("displayName") ?: context.getString(R.string.v3_empty)
            val name=BrandText.display(rawName)
            val state=if(driver) context.getString(R.string.seat_driver) else when(m?.optString("status")){"outside"->context.getString(R.string.outside);"boarded"->context.getString(R.string.boarded);"preparing"->context.getString(R.string.preparing);else->context.getString(R.string.v3_vacant_seat,index)}
            val color=if(driver||m?.optString("status")=="boarded") Palette.softMint else if(m!=null) Palette.warm else Palette.empty
            val tile=d.column().apply{
                gravity=Gravity.CENTER;setPadding(d.dp(3),d.dp(13),d.dp(3),d.dp(7));background=SeatDrawable(color,m!=null && m.optString("memberId")==highlightedMember)
                contentDescription=context.getString(R.string.v3_seat_description,index,name,state)
                isFocusable=interactive && m!=null;isClickable=isFocusable
                if(m!=null && interactive)setOnClickListener{choose(m)}
            }
            val avatar=AvatarView(context,m?.optJSONObject("avatar"),if(driver) context.getString(R.string.driver_avatar) else m?.optString("displayName")?:"+")
            tile.addView(avatar,LinearLayout.LayoutParams(d.dp(38),d.dp(38)).apply{gravity=Gravity.CENTER})
            tile.addView(d.single(name,19,Palette.forest,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
            tile.addView(d.text(state,16,if(m?.optString("status")=="outside")Palette.warning else Palette.secondary).apply{gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2))
            addView(tile);tiles.add(tile)
        }
    }
    override fun onMeasure(w: Int,h: Int){
        val width=MeasureSpec.getSize(w);val height=MeasureSpec.getSize(h);setMeasuredDimension(width,height)
        tiles.forEachIndexed { i,v->v.measure(MeasureSpec.makeMeasureSpec((width*if(i<2).37f else .29f).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((height*.30f).toInt(),MeasureSpec.EXACTLY)) }
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int){
        val xs=listOf(.1f,.53f,.045f,.355f,.665f)
        tiles.forEachIndexed{i,v->val x=(width*xs[i]).toInt();val y=(height*if(i<2).25f else .62f).toInt();v.layout(x,y,x+v.measuredWidth,y+v.measuredHeight)}
    }
    override fun onDraw(c: Canvas){
        val w=width.toFloat();val h=height.toFloat();p.style=Paint.Style.FILL;p.color=Color.WHITE
        c.drawRoundRect(w*.025f,h*.04f,w*.975f,h*.98f,w*.23f,w*.23f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d.dp(2).toFloat();p.color=Palette.line
        c.drawRoundRect(w*.025f,h*.04f,w*.975f,h*.98f,w*.23f,w*.23f,p)
        p.style=Paint.Style.FILL;p.color=Palette.softMint
        val windshield=Path().apply{moveTo(w*.15f,h*.18f);quadTo(w*.5f,h*.09f,w*.85f,h*.18f);lineTo(w*.81f,h*.22f);quadTo(w*.5f,h*.16f,w*.19f,h*.22f);close()};c.drawPath(windshield,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d.dp(3).toFloat();p.color=Palette.sage
        c.drawCircle(w*.27f,h*.23f,w*.057f,p);c.drawLine(w*.215f,h*.23f,w*.325f,h*.23f,p)
        p.color=Palette.line;c.drawLine(w*.49f,h*.32f,w*.49f,h*.52f,p)
        p.style=Paint.Style.FILL
    }
    private class SeatDrawable(private val color: Int,private val highlighted:Boolean):Drawable(){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c:Canvas){val w=bounds.width().toFloat();val h=bounds.height().toFloat();p.color=color
            c.drawRoundRect(w*.25f,0f,w*.75f,h*.16f,w*.10f,w*.10f,p)
            c.drawRoundRect(0f,h*.10f,w,h*.94f,w*.19f,w*.19f,p)
            if(highlighted){
                p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=Palette.forest
                c.drawRoundRect(2f,h*.10f,w-2f,h*.94f,w*.19f,w*.19f,p);p.style=Paint.Style.FILL
            }
            p.color=Color.argb(35,25,58,48);p.style=Paint.Style.STROKE;p.strokeWidth=2f
            c.drawLine(w*.13f,h*.91f,w*.87f,h*.91f,p);p.style=Paint.Style.FILL
        }
        override fun setAlpha(alpha:Int){p.alpha=alpha}
        override fun setColorFilter(filter:ColorFilter?){p.colorFilter=filter}
        @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
