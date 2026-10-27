package com.vime.ime
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
fun Context.uiColor(id: Int): Int {
    com.vime.ime.data.CustomTheme.color(this,id)?.let { return it }
    val attribute = Palette.attributes[id] ?: return ContextCompat.getColor(this, id)
    val value = TypedValue()
    if (!theme.resolveAttribute(attribute, value, true)) return ContextCompat.getColor(this, id)
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}
object Palette {
    val attributes = mapOf(
        R.color.ic_launcher_bg to R.attr.vimeIcLauncherBg,
        R.color.ink to R.attr.vimeInk,
        R.color.ink_2 to R.attr.vimeInk2,
        R.color.ink_3 to R.attr.vimeInk3,
        R.color.line to R.attr.vimeLine,
        R.color.line_2 to R.attr.vimeLine2,
        R.color.kb_bg to R.attr.vimeKbBg,
        R.color.key to R.attr.vimeKey,
        R.color.key_fn to R.attr.vimeKeyFn,
        R.color.key_press to R.attr.vimeKeyPress,
        R.color.key_edge to R.attr.vimeKeyEdge,
        R.color.cand_bg to R.attr.vimeCandBg,
        R.color.panel_bg to R.attr.vimePanelBg,
        R.color.acc to R.attr.vimeAcc,
        R.color.on_acc to R.attr.vimeOnAcc,
        R.color.acc_soft to R.attr.vimeAccSoft,
        R.color.acc_ink to R.attr.vimeAccInk,
        R.color.surface to R.attr.vimeSurface,
        R.color.surface_alt to R.attr.vimeSurfaceAlt,
        R.color.success to R.attr.vimeSuccess,
        R.color.lv0 to R.attr.vimeLv0,
        R.color.lv1 to R.attr.vimeLv1,
        R.color.lv2 to R.attr.vimeLv2
    )
}

