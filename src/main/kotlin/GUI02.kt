import lib.computeContours
import lib.kMeansClustering2
import lib.kmPP2
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.mask
import org.openrndr.extra.fx.blur.FrameBlur
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.removeLoops
import java.awt.Frame
import java.io.File

fun main() = application {
    configure {
        width = 640
        height = 640
    }
    program {

        val gui = GUI()

        val settings = object {
            @DoubleParameter("time smoothing", 0.05, 1.0)
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

        val cb = colorBuffer(width, height)

        val fb = FrameBlur()

        drawer.isolatedWithTarget(rt) {
            drawer.image(at,0)
        }

        val clusters = (0 until 30).map {
            val r = Rectangle(it.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
            val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }
            kmPP2(shad, 5).sortedBy { c -> c.h }
        }.toMutableList()

        fun calculateCentroids() {
            clusters.forEachIndexed { j, centroids ->
                val sorted = centroids.sortedBy { it.h }

                val r = Rectangle(j.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)
                val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }

                //val shake = sorted.map { it.first to it.second.plus(ColorRGBa.fromVector(Vector3.uniform(-0.01, 0.01)).toLABa()) }
                val clustering = kMeansClustering2(shad, sorted)
                val sortedClustering = clustering.sortedBy { it.h }

                clusters[j] = sortedClustering
            }
        }

        var currentIdx = -1

        extend(GUI()) {
            add(settings)
            visible = mouse.position.x < 200
        }
        extend(ScreenRecorder()) {
            enabled = false
            maximumFrames = 2048
            quitAfterMaximum = true
        }

        extend {

            drawer.clear(ColorRGBa.WHITE)

            drawer.isolatedWithTarget(rt) {
                drawer.image(at, currentIdx)
            }
            drawer.image(rt.colorBuffer(0))

            currentIdx++
            calculateCentroids()

            drawer.isolatedWithTarget(rt2) {
                drawer.clear(ColorRGBa.TRANSPARENT)

                drawer.rectangles {
                    clusters.forEachIndexed  { i, centroids ->
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

            fb.blend = settings.smoothingAmt
            fb.apply(rt2.colorBuffer(0), cb)
            drawer.image(cb)


        }
    }
}
