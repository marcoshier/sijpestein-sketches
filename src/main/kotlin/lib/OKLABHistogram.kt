package org.openrndr.extra.color.statistics

import org.openrndr.draw.ColorBuffer
import org.openrndr.extra.color.spaces.ColorOKLCHa
import org.openrndr.extra.color.spaces.toOKLCHa
import kotlin.random.Random


/* l, c: 0.0..1.0 - h: 0.0..360.0 */

fun ColorOKLCHa.binIndex(binCount: Int): Pair<Int, Int> {
    val cb = (c * binCount).toInt().coerceIn(0, binCount - 1)
    val hb = ((h / 360.0) * binCount).toInt().coerceIn(0, binCount - 1)
    return cb to hb
}

fun calculateHistogramOKLAB(buffer: ColorBuffer,
                          binCount: Int = 16,
                          weighting: ColorOKLCHa.() -> Double = { 1.0 },
                          downloadShadow: Boolean = true): OKLABHistogram {
    val bins = Array(binCount) { DoubleArray(binCount) }
    if (downloadShadow) {
        buffer.shadow.download()
    }


    var totalWeight = 0.0
    val s = buffer.shadow
    for (y in 0 until buffer.height) {
        for (x in 0 until buffer.width) {
            val c = s[x, y]

            if (c.alpha == 1.0) {     // filtering out transparent (masked out) pixels
                val ok = c.toOKLCHa()
                val weight = ok.weighting()
                val (cb, hb) = ok.binIndex(binCount)
                bins[cb][hb] += weight
                totalWeight += weight
            }

        }
    }

    if (totalWeight > 0)
        for (c in 0 until binCount) {
            for (h in 0 until binCount) {
                bins[c][h] /= totalWeight
            }
        }

    return OKLABHistogram(bins, binCount)
}


class OKLABHistogram(val freqs: Array<DoubleArray>, val binCount: Int) {
    fun frequency(color: ColorOKLCHa): Double {
        val (cb, hb) = color.binIndex(binCount)
        return freqs[cb][hb]
    }

    fun color(cBin: Int, hBin: Int): ColorOKLCHa =
        ColorOKLCHa(0.5, cBin / (binCount - 1.0), hBin / (binCount - 1.0))

    fun sample(random: Random = Random.Default): ColorOKLCHa {
        val x = random.nextDouble()
        var sum = 0.0
        for (c in 0 until binCount) {
            for (h in 0 until binCount) {
                sum += freqs[c][h]
                if (sum >= x) {
                    return color(c, h)
                }
            }
        }
        return color(binCount - 1, binCount - 1)
    }

    fun sortedColors(): List<Pair<ColorOKLCHa, Double>> {
        val result = mutableListOf<Pair<ColorOKLCHa, Double>>()
        for (c in 0 until binCount) {
            for (h in 0 until binCount) {
                result += Pair(
                    ColorOKLCHa(0.5, c / (binCount - 1.0), h / (binCount - 1.0)).also { println(it) },
                    freqs[c][h]
                )
            }
        }
        return result.sortedByDescending { it.second }
    }
}