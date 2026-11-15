package com.hopon.car.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.*

object Palette {
    val background = Color.parseColor("#F6F3F2")
    val softMint = Color.parseColor("#E4DCD3")
    val mint = Color.parseColor("#002C5F")
    val forest = Color.parseColor("#002C5F")
    val secondary = Color.parseColor("#002C5F")
    val sage = Color.parseColor("#A36B4F")
    val line = Color.parseColor("#E4DCD3")
    val empty = Color.parseColor("#E4DCD3")
    val warning = Color.parseColor("#A36B4F")
    val warm = Color.parseColor("#E4DCD3")
}

class Design(private val context: Context) {
    val scale get() = (context.resources.configuration.screenWidthDp / 1400f).coerceIn(1f, 1.2f)
    fun dp(n: Int) = (n * context.resources.displayMetrics.density * scale).toInt()
    fun background(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun text(value: String, size: Int = 22, color: Int = Palette.forest, bold: Boolean = false) = TextView(context).apply {
        text = BrandText.display(value); textSize = size * scale; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(3), 0, dp(3))
    }
    fun single(value: String, size: Int = 22, color: Int = Palette.forest, bold: Boolean = false) = text(value,size,color,bold).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    fun column(color: Int? = null, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding),dp(padding),dp(padding),dp(padding))
        if (color != null) background = background(color)
    }
    fun button(title: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) = Button(context).apply {
        text = BrandText.display(title); textSize = 22 * scale; isAllCaps = false; gravity = Gravity.CENTER
        setTextColor(if(primary) Color.WHITE else Palette.forest); background = background(if(primary) Palette.mint else Palette.softMint,18)
        minHeight = dp(64); minimumHeight = dp(64); setPadding(dp(12),dp(4),dp(12),dp(4))
        isEnabled = enabled; alpha = if(enabled) 1f else .45f
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setOnClickListener { click() }
    }
}
