package com.example.formulario

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * Aplica una fuente concreta (p. ej. Quicksand SemiBold) a un tramo de texto.
 * TypefaceSpan(Typeface) solo existe desde API 28 y StyleSpan(BOLD) inventaría una
 * negrita artificial, así que se usa este span propio.
 */
class FuenteSpan(private val fuente: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = fuente
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = fuente
    }
}
