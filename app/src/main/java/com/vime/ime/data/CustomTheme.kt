package com.vime.ime.data

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.vime.ime.Palette
import com.vime.ime.R
import org.json.JSONObject
import kotlin.math.pow

data class CustomColors(val name: String = "我的配色", val key: String = "#FFFFFF", val keyboard: String = "#E9EDFA",
    val ink: String = "#18213D", val accent: String = "#405AC6", val panel: String = "#F5F7FC")
object ThemeCodec {
    fun color(s: String): Int { require(s.matches(Regex("#[0-9A-Fa-f]{6}"))) { "颜色请填写 #RRGGBB" }; return (s.drop(1).toLong(16) or 0xff000000).toInt() }
    fun contrast(a: Int, b: Int): Double {
        fun lum(n: Int): Double {
            fun c(shift: Int): Double { val x = ((n ushr shift) and 255) / 255.0; return if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4) }
            return .2126 * c(16) + .7152 * c(8) + .0722 * c(0)
        }
        val x = lum(a); val y = lum(b); return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
    fun validate(c: CustomColors) {
        require(c.name.isNotBlank() && c.name.length <= 40) { "配色名称需为1—40个字符" }
        val text = color(c.ink)
        require(listOf(c.key,c.keyboard,c.panel).all { contrast(text,color(it)) >= 4.5 }) { "文字与键面、背景的对比不足，请加深文字或调整背景" }
        color(c.accent)
    }
    fun write(c: CustomColors): String { validate(c); return JSONObject().put("format","vime-theme-1").put("name",c.name).put("key",c.key).put("keyboard",c.keyboard).put("ink",c.ink).put("accent",c.accent).put("panel",c.panel).toString(2) }
    fun read(s: String): CustomColors {
        require(s.toByteArray().size <= 16384) { "配色文件过大" }
        val o = JSONObject(s); require(o.getString("format") == "vime-theme-1") { "不是可用的配色文件" }
        return CustomColors(o.getString("name"),o.getString("key"),o.getString("keyboard"),o.getString("ink"),o.getString("accent"),o.getString("panel")).also(::validate)
    }
    fun mix(a: Int,b: Int,f: Double): Int {
        var out = 0xff000000.toInt(); for (shift in listOf(0,8,16)) out = out or (((((a ushr shift) and 255)*(1-f)+((b ushr shift) and 255)*f).toInt()) shl shift)
        return out
    }
}
object CustomTheme {
    private fun prefs(c: Context) = c.getSharedPreferences("vime_appearance",Context.MODE_PRIVATE)
    fun read(c: Context) = runCatching { ThemeCodec.read(prefs(c).getString("custom_theme", "")!!) }.getOrDefault(CustomColors())
    fun save(c: Context, colors: CustomColors) {
        prefs(c).edit().putString("custom_theme",ThemeCodec.write(colors)).putLong("custom_version",System.currentTimeMillis()).apply()
        ThemePref.setSkin(c,6)
    }
    fun version(c: Context) = prefs(c).getLong("custom_version",0)
    private var cached: Pair<CustomColors,Map<Int,Int>>? = null
    @Synchronized private fun palette(c: Context): Map<Int,Int> {
        val v = read(c); cached?.takeIf { it.first == v }?.let { return it.second }
        val key = ThemeCodec.color(v.key); val panel = ThemeCodec.color(v.panel); val ink = ThemeCodec.color(v.ink); val accent = ThemeCodec.color(v.accent)
        val soft = ThemeCodec.mix(panel,accent,.08)
        fun safeKey(f: Double) = ThemeCodec.mix(key,accent,f).takeIf { ThemeCodec.contrast(it,ink)>=4.5 } ?: key
        val map = mapOf(R.color.key to key,R.color.key_fn to safeKey(.10),R.color.key_press to safeKey(.20),R.color.key_edge to ThemeCodec.mix(key,ink,.25),
            R.color.ink to ink,R.color.ink_2 to ink,R.color.ink_3 to ink,R.color.kb_bg to ThemeCodec.color(v.keyboard),R.color.panel_bg to panel,
            R.color.cand_bg to panel,R.color.surface to panel,R.color.surface_alt to panel,R.color.acc to accent,
            R.color.acc_soft to soft,R.color.acc_ink to (accent.takeIf { ThemeCodec.contrast(it,soft)>=4.5 } ?: ink),
            R.color.on_acc to (if (ThemeCodec.contrast(accent,-1)>=ThemeCodec.contrast(accent,0xff000000.toInt())) -1 else 0xff000000.toInt()),
            R.color.line to ThemeCodec.mix(panel,ink,.2),R.color.line_2 to ThemeCodec.mix(panel,ink,.3))
        cached = v to map; return map
    }
    fun color(c: Context,id: Int): Int? = if (ThemePref.skin(c)==6) palette(c)[id] else null
    fun apply(root: View) {
        val c = root.context; if (ThemePref.skin(c)!=6) return
        val colors = palette(c)
        fun original(id: Int): Int? {
            val attr = Palette.attributes[id] ?: return null; val value = TypedValue()
            if (!c.theme.resolveAttribute(attr,value,true)) return null
            return if (value.resourceId != 0) c.getColor(value.resourceId) else value.data
        }
        val textIds = listOf(R.color.ink,R.color.ink_2,R.color.ink_3,R.color.acc,R.color.acc_ink,R.color.on_acc)
        val textMap = textIds.mapNotNull { id -> original(id)?.let { old -> old to colors.getValue(id) } }.toMap()
        val backgroundMap = colors.filterKeys { it !in textIds }.mapNotNull { (id,value) -> original(id)?.let { old -> old to value } }.toMap()
        fun drawable(d: Drawable?) {
            if (d is GradientDrawable) d.color?.defaultColor?.let { old -> backgroundMap[old]?.let { d.mutate(); d.setColor(it) } }
            if (d is android.graphics.drawable.ColorDrawable) backgroundMap[d.color]?.let { d.mutate(); d.color=it }
            if (d is LayerDrawable) for (i in 0 until d.numberOfLayers) drawable(d.getDrawable(i))
        }
        fun visit(v: View) {
            if (v is android.widget.ImageView) v.imageTintList = ColorStateList.valueOf(colors.getValue(R.color.ink))
            if (v is TextView) {
                textMap[v.currentTextColor]?.let(v::setTextColor)
                textMap[v.currentHintTextColor]?.let(v::setHintTextColor)
            }
            drawable(v.background)
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }
}
