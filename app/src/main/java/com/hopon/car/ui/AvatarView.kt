package com.hopon.car.ui

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONObject

/** Small vector profile, using the same avatar fields as the mobile contract. */
class AvatarView(context: Context, private val avatar: JSONObject?, private val fallback: String) : View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val radius=width.coerceAtMost(height)/2f;val x=width/2f;val y=height/2f
        p.color=runCatching { Color.parseColor(avatar?.optString("color") ?: "#E4DCD3") }.getOrDefault(Palette.softMint)
        c.drawCircle(x,y,radius,p)
        if(avatar==null){p.color=Palette.forest;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);p.textSize=radius*.9f;c.drawText(BrandText.display(fallback).take(1),x,y-(p.ascent()+p.descent())/2,p);return}
        p.color=Palette.forest;c.drawOval(x-radius*.64f,y+radius*.4f,x+radius*.64f,y+radius,p)
        p.color=Color.rgb(242,204,174);c.drawOval(x-radius*.46f,y-radius*.57f,x+radius*.46f,y+radius*.55f,p)
        p.color=Color.rgb(67,51,43)
        c.drawArc(x-radius*.5f,y-radius*.67f,x+radius*.5f,y+radius*.15f,185f,190f,true,p)
        if(avatar.optInt("hair")==1)c.drawCircle(x+radius*.35f,y-radius*.6f,radius*.2f,p)
        if(avatar.optInt("hair")==2)c.drawOval(x-radius*.54f,y-radius*.2f,x-radius*.35f,y+radius*.45f,p)
        p.color=Palette.forest;c.drawCircle(x-radius*.16f,y+radius*.05f,radius*.035f,p);c.drawCircle(x+radius*.16f,y+radius*.05f,radius*.035f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=radius*.045f
        c.drawArc(x-radius*.16f,y+radius*.15f,x+radius*.16f,y+radius*.37f,0f,180f,false,p);p.style=Paint.Style.FILL
    }
}
