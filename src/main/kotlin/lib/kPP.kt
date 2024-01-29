package lib

import org.openrndr.color.ColorLABa
import org.openrndr.draw.ColorBufferShadow
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.noise.uniform
import org.openrndr.math.IntVector2
import kotlin.math.pow


fun kmPP(shad: ColorBufferShadow, k: Int = 5, res: Int = 5): List<ColorLABa> {

    val w = shad.colorBuffer.width
    val h = shad.colorBuffer.height

    val initialPoint = IntVector2.uniform(IntVector2.ZERO, IntVector2(w, h))

    val centroids = mutableListOf(
        shad[initialPoint.x, initialPoint.y].toLABa()
    )

    while (centroids.size < k) {

        val weights = (0 until w step res).flatMap { x ->
            (0 until h step res).map { y ->
                val lab = shad[x, y].toLABa()
                val nearest = centroids.minBy { it.deltaE76(lab) }

                lab to lab.deltaE76(nearest).pow(2)
            }
        }.filter { it.first.alpha == 1.0 }

        val weightSum = weights.sumOf { it.second }
        val randomValue = Double.uniform(0.0, weightSum)

        var accumulation = 0.0
        var candidate: ColorLABa? = null

        for (colorToDistance in weights) {
            accumulation += colorToDistance.second
            if (accumulation >= randomValue) {
                candidate = colorToDistance.first
                break
            }
        }

        require(candidate != null)

        centroids.add(candidate)
    }


    return centroids
}

fun kMeansClustering(shad: ColorBufferShadow, centroids: List<ColorLABa>, maxIterations: Int = 150, res: Int = 5): Map<ColorLABa, MutableList<Pair<IntVector2, ColorLABa>>> {

    var l = 0

    var centers = centroids

    var clusters = centers.associateWith { mutableListOf<Pair<IntVector2, ColorLABa>>() }

    while (l < maxIterations) {

        clusters = centers.associateWith { mutableListOf() }

        for (x in 0 until shad.colorBuffer.width step res) {
            for (y in 0 until shad.colorBuffer.height step res) {

                val rgb = shad[x, y]
                val color = rgb.toLABa()
                val closestCluster = centers.minBy { color.deltaE76(it) }

                if(rgb.luminance > 0.1 && rgb.alpha == 1.0) {
                    clusters[closestCluster]?.add(IntVector2(x, y) to color)
                }
            }
        }

        centers = centers.map { c ->
            val colors = clusters[c]!!.filter { it.second.alpha != 0.0 }
            val sum = colors.fold(ColorLABa(0.0, 0.0, 0.0)) { acc, n -> acc + n.second }
            val average = sum / colors.size.toDouble()

            average
        }

        l++

    }

    return clusters
}