package com.vime.ime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Only the keyboard body determines window height; sheets fill that exact area. */
class ImeRootLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val body = findViewById<View>(R.id.keyboardBody) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChildWithMargins(body, widthMeasureSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0)
        val w = resolveSize(body.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val h = resolveSize(body.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === body || child.visibility == GONE) continue
            measureChildWithMargins(child, MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), 0,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY), 0)
        }
    }
}
