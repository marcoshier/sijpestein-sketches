package lib

import org.openrndr.math.Polar
import org.openrndr.shape.contour

class Arc(val start: Double, val radius: Double, val length: Double, val height: Double) {
    fun split(offset: Double = 0.0): List<Arc> {
        val hl = length / 2.0
        return listOf(Arc(start, radius + offset, hl, height), Arc(start + hl, radius + offset, hl, height))
    }

    val contour
        get() = contour {
            moveTo(Polar(start, radius).cartesian)
            arcTo(radius, radius, length, false, true, Polar(start + length, radius).cartesian)
            lineTo(Polar(start + length, radius + height).cartesian)
            arcTo(radius + height, radius + height, length, false, false, Polar(start, radius + height).cartesian)
            lineTo(anchor)
            close()
        }
}
