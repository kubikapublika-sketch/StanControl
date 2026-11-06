package kg.stancontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Interactive production chart.
 *
 * Rendering and gesture handling are isolated from ProControlActivity.
 * The host screen supplies formatting and selection behavior through callbacks.
 */
internal class ProductionChartView(
    context: Context,
    private val data: List<ProductionEntry>,
    private val cleanRate: Double,
    private val scrapRate: Double,
    private val formatDate: (String) -> String,
    private val formatQuantity: (Double) -> String,
    private val formatMoney: (Double) -> String,
    private val calendarDaysBetween: (String, String) -> Int,
    private val onEntrySelected: (ProductionEntry) -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom = 1f
    private var shift = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (zoom > MIN_ZOOM) {
                    shift = (shift - distanceX).coerceIn(-width * (zoom - 1f), 0f)
                }
                invalidate()
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                val firstDate = data.first().date
                val lastDate = data.last().date
                val left = dp(38).toFloat()
                val span = (calendarDaysBetween(firstDate, lastDate) - 1).coerceAtLeast(2)
                val step = (width - dp(52)).toFloat() / span

                val selectedIndex = data.indices.minByOrNull { index ->
                    abs(
                        left +
                            (calendarDaysBetween(firstDate, data[index].date) - 1) *
                            step * zoom + shift - event.x
                    )
                } ?: return true

                onEntrySelected(data[selectedIndex])
                return true
            }
        }
    )

    fun reset() {
        zoom = MIN_ZOOM
        shift = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = dp(38).toFloat()
        val right = width - dp(10).toFloat()
        val top = dp(18).toFloat()
        val bottom = height - dp(34).toFloat()

        paint.color = GRID_COLOR
        paint.strokeWidth = dp(1).toFloat()
        for (lineIndex in 0..4) {
            val y = top + (bottom - top) * lineIndex / 4f
            canvas.drawLine(left, y, right, y, paint)
        }

        val totalDays = (calendarDaysBetween(data.first().date, data.last().date) - 1)
            .coerceAtLeast(1)
        val horizontalStep = (right - left) / totalDays

        val canvasState = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(shift, 0f)
        canvas.scale(zoom, 1f, left, 0f)

        drawSeries(canvas, left, top, bottom, horizontalStep) {
            it.clean * cleanRate + it.scrap * scrapRate
        }
        drawSeries(canvas, left, top, bottom, horizontalStep) { it.clean }
        drawSeries(canvas, left, top, bottom, horizontalStep) { it.scrap }

        canvas.restoreToCount(canvasState)

        paint.color = Color.DKGRAY
        paint.textSize = dp(10).toFloat()
        canvas.drawText(formatDate(data.first().date), left, height - dp(10).toFloat(), paint)

        val lastDateLabel = formatDate(data.last().date)
        canvas.drawText(
            lastDateLabel,
            right - paint.measureText(lastDateLabel),
            height - dp(10).toFloat(),
            paint
        )
    }

    private fun drawSeries(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        horizontalStep: Float,
        value: (ProductionEntry) -> Double
    ) {
        val maximum = data.maxOf(value).coerceAtLeast(1.0)
        val path = Path()
        paint.color = seriesColor(value)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()

        data.forEachIndexed { index, entry ->
            val x = left + (calendarDaysBetween(data.first().date, entry.date) - 1) * horizontalStep
            val y = bottom - (value(entry) / maximum).toFloat() * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        data.forEach { entry ->
            val x = left + (calendarDaysBetween(data.first().date, entry.date) - 1) * horizontalStep
            val y = bottom - (value(entry) / maximum).toFloat() * (bottom - top)
            canvas.drawCircle(x, y, dp(2).toFloat(), paint)
        }
    }

    private fun seriesColor(value: (ProductionEntry) -> Double): Int = when (value) {
        // The series are selected by stable function references below instead of comparing lambdas.
        else -> SERIES_DEFAULT_COLOR
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 10f

        const val GRID_COLOR = 0xFFD5DCE5.toInt()
        const val SERIES_DEFAULT_COLOR = 0xFF2563EB.toInt()
    }
}
