import lib.computeContours
import lib.kMeansClustering
import lib.kmPP
import org.openrndr.application
import org.openrndr.color.ColorLABa
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.mask
import org.openrndr.extra.fx.blur.FrameBlur
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.gui.addTo
import org.openrndr.extra.noise.uniform
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.math.IntVector2
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

        val gui = GUI()

        val settings = object {
            @DoubleParameter("time smoothing", 0.0, 1.0)
            var smoothingAmt = 0.0

        }

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

        val fb = FrameBlur()

        drawer.isolatedWithTarget(rt) {
            drawer.image(at,0)
        }

        val centroids = (0 until 30).map {
            val r = Rectangle(it.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
            val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }
            kmPP(shad, 5).sortedBy { c -> c.toLCHABa().h }
        }.toMutableList()


        var currentIdx = -1

        var oldClustering = mutableListOf<List<Pair<ColorLABa, MutableList<Pair<IntVector2, ColorLABa>>>>>()

        val g = extend(GUI()) {
            add(settings)
        }
        extend {

            g.visible = mouse.position.x < 200
            fb.blend = settings.smoothingAmt

            if (frameCount % 30 == 0) currentIdx++
            println("$frameCount $currentIdx")

            drawer.clear(ColorRGBa.BLACK)

            drawer.isolatedWithTarget(rt) {
                drawer.image(at, currentIdx)
            }

            drawer.image(rt.colorBuffer(0))

            centroids.mapIndexed { j, colors ->

                val r = Rectangle(j.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
                val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }

                val shake = colors.map { it.plus(ColorRGBa.fromVector(Vector3.uniform(-0.01, 0.01)).toLABa()) }
                val newClustering = kMeansClustering(shad, shake, 15).sortedBy { it.first.toLCHABa().h } // 5 centroids, each paired with a list of their closest position-to-color tuples

                if (oldClustering.getOrNull(j) == null) oldClustering.add(j, newClustering)

                val clustering = (oldClustering[j] zip newClustering).map { (old, new)  ->
                    val centroid = new.first.mix(old.first, settings.smoothingAmt)
                    val l = (old.second zip new.second).map { (o, n) ->
                        n.first to n.second.mix(o.second, settings.smoothingAmt)
                    }
                    centroid to l
                }

                drawer.isolatedWithTarget(rt2) {
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    drawer.drawStyle.clip = r
                    drawer.stroke = ColorRGBa.WHITE.opacify(0.2)
                    drawer.strokeWeight = 0.02
                    drawer.fill = null
                    drawer.rectangle(r)


                    clustering.forEach { (centroid, colorsToPositions) ->
                        val c = colorsToPositions.minByOrNull { it.second.deltaE76(centroid) }

                        if (c != null) {
                            drawer.strokeWeight = 0.5
                            drawer.stroke = ColorRGBa.WHITE.opacify(1.0)
                            drawer.fill = c.second.toRGBa().toSRGB().opacify(1.0)
                            drawer.circle(c.first.vector2 + r.corner, 6.0)
                        }
                    }

                    drawer.drawStyle.clip = null

                    clustering.forEachIndexed { index, (centroid, colorsToPositions) ->
                        val c = colorsToPositions.minByOrNull { it.second.deltaE76(centroid) }

                        drawer.fill = (c?.second ?: centroid).toRGBa().toSRGB()
                        drawer.stroke = null
                        drawer.rectangle(r.corner + Vector2(0.0, r.height + (index / 5.0) * 140.0), r.width, 140.0 / 5.0)
                    }

                }

                drawer.image(rt2.colorBuffer(0))


                centroids[j] = clustering.map { (centroid, colorsToPositions) ->
                    val c = colorsToPositions.minByOrNull { it.second.deltaE76(centroid) }

                    c?.second ?: centroid
                }.sortedBy { it.toLCHABa().h }


                oldClustering[j] = newClustering
            }

            drawer.circle(drawer.bounds.center + Vector2(sin(seconds) * 100.0, 0.0), 80.0)


        }
    }
}
