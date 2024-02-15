package lib

import org.openrndr.color.ColorLCHABa
import org.openrndr.draw.ColorBufferShadow
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.noise.uniform
import org.openrndr.math.IntVector2
import kotlin.math.pow


fun kmPP2(shad: ColorBufferShadow, k: Int = 5, res: Int = 5): List<ColorLCHABa> {

    val w = shad.colorBuffer.width
    val h = shad.colorBuffer.height

    var initialPoint = IntVector2.uniform(IntVector2.ZERO, IntVector2(w, h))
    while (shad[initialPoint.x, initialPoint.y].alpha != 1.0) initialPoint = IntVector2.uniform(IntVector2.ZERO, IntVector2(w, h))

    val centroids = mutableListOf(
        shad[initialPoint.x, initialPoint.y].toLCHABa()
    )

    while (centroids.size < k) {

        val weights = (0 until w step res).flatMap { x ->
            (0 until h step res).map { y ->
                val lab = shad[x, y].toLCHABa()//.copy(l = 50.0)
                val nearest = centroids.minBy { it.deltaE76(lab) }

                lab to lab.deltaE76(nearest).pow(2)
            }
        }.filter { it.first.alpha == 1.0 }

        val weightSum = weights.sumOf { it.second }
        val randomValue = Double.uniform(0.0, weightSum)

        var accumulation = 0.0
        var candidate: ColorLCHABa? = null

        for (colorToDistance in weights) {
            accumulation += colorToDistance.second
            if (accumulation >= randomValue) {
                candidate = colorToDistance.first
                break
            }
        }

        require(candidate != null && candidate.alpha == 1.0)

        centroids.add(candidate)
    }


    return centroids
}

fun kMeansClustering2(shad: ColorBufferShadow, centroids: List<ColorLCHABa>, maxIterations: Int = 15, res: Int = 5): List<ColorLCHABa> {

    var i = 0

    var centers = centroids

    while (i < maxIterations) {

        val clusters = centers.map { it to mutableListOf<ColorLCHABa>() }

        for (x in 0 until shad.colorBuffer.width step res) {
            for (y in 0 until shad.colorBuffer.height step res) {

                val rgb = shad[x, y]
                val color = rgb.toLCHABa()

                val closestCluster = centers.minBy { color.deltaE76(it) }
                val idx = centers.indexOf(closestCluster)

                if(rgb.alpha == 1.0) {
                    clusters[idx].second.add(color)
                }
            }
        }

        centers = clusters.map { (centroid, colors) ->
            if (colors.isNotEmpty()) {
                val sum = colors.reduce { acc, new -> acc + new  }
                val average = sum / colors.size.toDouble()

                average  //...meh
            } else {
                centroid
            }
        }

        i++

    }

    return centers
}