package io.github.figs0203.afinador.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import io.github.figs0203.afinador.R
import io.github.figs0203.afinador.music.ChordShape

/**
 * Draws a guitar chord diagram: six strings, five frets, open and muted strings
 * above the nut, a barre where the shape needs one and the fret number when the
 * chord starts up the neck. It is a plain Canvas view, so it needs no library
 * and works offline.
 */
class ChordDiagramView(context: Context) : View(context) {

    private var shape: ChordShape? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = context.getColor(R.color.stroke)
    }

    private val nutPaint = Paint(linePaint).apply {
        strokeWidth = dp(3f)
        color = context.getColor(R.color.text_secondary)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.accent)
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }

    private val fretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.RIGHT
        textSize = sp(11f)
    }

    fun setShape(shape: ChordShape) {
        this.shape = shape
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(190f).toInt(), widthMeasureSpec),
            resolveSize(dp(230f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val shape = shape ?: return

        val side = dp(40f)
        val boardWidth = (width - 2 * side).coerceIn(dp(120f), dp(260f))
        val left = (width - boardWidth) / 2f
        val right = left + boardWidth
        val top = paddingTop + dp(34f)
        val bottom = height - paddingBottom - dp(16f)
        val fretHeight = (bottom - top) / 5f
        val stringStep = (right - left) / 5f

        fun xOf(string: Int) = left + string * stringStep
        fun yOf(fret: Int) = top + (fret - shape.baseFret + 0.5f) * fretHeight

        // Strings.
        for (string in 0..5) {
            canvas.drawLine(xOf(string), top, xOf(string), top + 5 * fretHeight, linePaint)
        }

        // Frets. The first line is the nut only when the shape starts there.
        for (fret in 0..5) {
            val y = top + fret * fretHeight
            val paint = if (fret == 0 && shape.baseFret == 1) nutPaint else linePaint
            canvas.drawLine(left, y, right, y, paint)
        }

        if (shape.baseFret > 1) {
            canvas.drawText(
                shape.baseFret.toString(),
                left - dp(8f),
                top + fretHeight * 0.65f,
                fretPaint
            )
        }

        // Open and muted strings sit above the nut.
        val markerY = top - dp(10f)
        shape.frets.forEachIndexed { string, fret ->
            when (fret) {
                0 -> canvas.drawText("O", xOf(string), markerY, markerPaint)
                -1 -> canvas.drawText("×", xOf(string), markerY, markerPaint)
            }
        }

        val played = shape.frets.mapIndexedNotNull { string, fret ->
            if (fret > 0) string to fret else null
        }
        if (played.isEmpty()) return

        val radius = minOf(stringStep, fretHeight) * 0.30f
        val lowest = played.minOf { it.second }
        val firstSounding = shape.frets.indexOfFirst { it >= 0 }
        val atLowest = played.filter { it.second == lowest }.map { it.first }
        val barre = firstSounding >= 0 &&
            shape.frets[firstSounding] == lowest &&
            atLowest.size >= 2

        if (barre) {
            val barLeft = xOf(atLowest.first()) - radius
            val barRight = xOf(atLowest.last()) + radius
            val barTop = yOf(lowest) - radius
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barRight, barTop + 2 * radius),
                radius,
                radius,
                dotPaint
            )
            played.filter { it.second != lowest }.forEach { (string, fret) ->
                canvas.drawCircle(xOf(string), yOf(fret), radius, dotPaint)
            }
        } else {
            played.forEach { (string, fret) ->
                canvas.drawCircle(xOf(string), yOf(fret), radius, dotPaint)
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
