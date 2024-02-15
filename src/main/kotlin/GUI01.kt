import lib.computeContours
import lib.kMeansClustering
import lib.kMeansClustering2
import lib.kmPP2
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.mask
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.noise.uniform
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.mix
import org.openrndr.shape.Rectangle
import org.openrndr.shape.removeLoops
import java.io.File
import kotlin.math.sin

fun main() = application {
    configure {
        width = 640
        height = 640
    }
    program {
/*
        extend(ScreenRecorder()) {
            maximumFrames = 2048
            quitAfterMaximum = true
        }*/
        val f = File("data/video-frames-cms").listFiles()!!.filter { it.isFile }.sortedBy { it.nameWithoutExtension.toInt() }.takeLast(400)

        val at = arrayTexture(width, height, f.size)

        f.forEachIndexed { i, file ->
            println("$i / ${f.size}")
            val img = loadImage(file)
            val c = compose {
                draw {
                    drawer.image(img)
                }
                mask { drawer.shape(computeContours(loadImage(f[0])).maxBy { it.bounds.area }.removeLoops().shape) }
            }
            c.draw(drawer)
            c.result.copyTo(at, i)
        }

        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        val rt2 = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        drawer.isolatedWithTarget(rt) {
            drawer.image(at,0)
        }

        val allCentroids = (0 until 30).map {
            val r = Rectangle(it.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
            val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }
            kmPP2(shad, 5).sortedBy { c -> c.h }
        }.toMutableList()


        var currentIdx = -1

        extend {
            currentIdx++
            println("$frameCount $currentIdx")

            drawer.clear(ColorRGBa.BLACK)

            drawer.isolatedWithTarget(rt) {
                drawer.image(at, currentIdx)
            }

            drawer.image(rt.colorBuffer(0))

            allCentroids.forEachIndexed { j, centroids ->

                val sorted = centroids.sortedBy { it.h }

                val r = Rectangle(j.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
                val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }

                //val shake = sorted.map { it.first to it.second.plus(ColorRGBa.fromVector(Vector3.uniform(-0.01, 0.01)).toLABa()) }
                val clustering = kMeansClustering2(shad, sorted)
                val sortedClustering = clustering.sortedBy { it.h }

                allCentroids[j] = sortedClustering
            }

            drawer.isolatedWithTarget(rt2) {
                drawer.clear(ColorRGBa.TRANSPARENT)

                drawer.rectangles {
                    allCentroids.forEachIndexed  { i, centroids ->
                        val r = Rectangle(i.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)

                        this.stroke = ColorRGBa.WHITE.opacify(0.2)
                        this.strokeWeight = 0.02
                        this.fill = null
                        this.rectangle(r)

                        centroids.forEachIndexed { index, color ->
                            this.fill = color.toRGBa().toSRGB()
                            this.stroke = null
                            this.rectangle(r.corner + Vector2(0.0, r.height + (index / 5.0) * 140.0), r.width, 140.0 / 5.0)
                        }
                    }
                }
            }

            drawer.image(rt2.colorBuffer(0))

            drawer.circle(drawer.bounds.center + Vector2(sin(seconds) * 100.0, 0.0), 80.0)

        }
    }
}
