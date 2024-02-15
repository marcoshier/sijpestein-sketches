
import lib.computeContours
import lib.kMeansClustering2
import lib.kmPP2
import org.openrndr.ExtensionStage
import org.openrndr.application
import org.openrndr.color.ColorLCHABa
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.camera.Camera2D
import org.openrndr.extra.compositor.*
import org.openrndr.extra.fx.Post
import org.openrndr.extra.fx.blur.FrameBlur
import org.openrndr.extra.fx.blur.GaussianBlur
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.gui.addTo
import org.openrndr.extra.noclear.NoClear
import org.openrndr.extra.parameters.BooleanParameter
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.extra.viewbox.viewBox
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.IntVector2
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.removeLoops
import java.awt.Frame
import java.io.File

fun main() = application {
    configure {
        width = 640
        height = 640
        position = IntVector2(5000, 1000)
    }
    program {
        extend(ScreenRecorder()) {
            enabled = true
            maximumFrames = 2048
            quitAfterMaximum = true
        }

        val settings = object {
            @DoubleParameter("time smoothing", 0.05, 1.0)
            var smoothingAmt = 0.0

            @DoubleParameter("Side blending", 0.0, 1.0)
            var sideBlending = 0.0

            @DoubleParameter("Blur", 0.001, 1.0)
            var blur = 0.0

            @BooleanParameter("camera view")
            var cameraView = false

        }


        val f = File("data/video-frames-cms").listFiles()!!.filter { it.isFile }.sortedBy { it.nameWithoutExtension.toInt() }.take(400)

        val at = arrayTexture(width, width, f.size)

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

        val fb = FrameBlur().apply {
            blend = 0.05
        }

        drawer.isolatedWithTarget(rt) {
            drawer.image(at,0)
        }

        val clusters = (0 until 45).map {
            val r = Rectangle(it.toDouble() / 45.0 * width, 350.0, width / 45.0, 150.0)
            val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }
            kmPP2(shad, 5).sortedBy { c -> c.h }
        }.toMutableList()

        fun calculateCentroids() {
            clusters.forEachIndexed { j, centroids ->
                val sorted = centroids.sortedBy { it.h }

                val r = Rectangle(j.toDouble() / 45.0 * width, 350.0, width / 45.0, 150.0)
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
            compartmentsCollapsedByDefault = false
            visible = mouse.position.x < 200
        }

        val r2 = Rectangle(0.0, 500.0, width * 1.0, 25.0)
        val vb = viewBox(drawer.bounds) {

            val c = extend(Camera2D())
            val comp = compose {
                val a = aside {
                    draw {
                        drawer.defaults()
                        drawer.clear(ColorRGBa.TRANSPARENT)

                        val line = clusters.map { it.first() }

                        drawer.rectangles {
                            line.forEachIndexed { index, color ->
                                val before: ColorLCHABa? = line.getOrNull(index - 1)
                                val after: ColorLCHABa? = line.getOrNull(index + 1)

                                var finalColor = color
                                if (before != null && after != null) {
                                    finalColor = finalColor.mix(before, settings.sideBlending * 0.25)
                                    finalColor = finalColor.mix(after, settings.sideBlending * 0.25)
                                } else if (before == null && after != null) {
                                    finalColor = finalColor.mix(after, settings.sideBlending * 0.5)
                                } else if (before != null) {
                                    finalColor = finalColor.mix(before, settings.sideBlending * 0.5)
                                }

                                this.fill = finalColor.toRGBa().toSRGB()
                                this.stroke = null
                                this.rectangle((index / clusters.size.toDouble()) * width, 500.0, width / 45.0, 25.0)
                            }
                        }
                    }
                    post(FrameBlur()) {
                        blend = 1.0-settings.smoothingAmt
                    }
                    if (settings.blur > 0.0) {
                        post(GaussianBlur()) {
                            window = 25
                            sigma = settings.blur * 3.0
                        }
                    }
                }
                layer {
                    draw {
                        if (settings.cameraView) {
                            drawer.drawStyle.clip = r2.contour.transform(c.view).bounds
                            drawer.view = c.view
                        } else {
                            drawer.defaults()
                        }
                        drawer.image(a)
                        drawer.drawStyle.clip = null

                        drawer.stroke = if (settings.cameraView) ColorRGBa.WHITE else null
                        drawer.strokeWeight = 2.0
                        drawer.fill = null
                        drawer.rectangle(r2)
                    }
                }
            }
            extend {
                c.enabled = settings.cameraView
                drawer.clear(ColorRGBa.TRANSPARENT)

                comp.draw(drawer)

            }
        }

        vb.mouse.buttonUp.listeners.reverse()
        vb.mouse.buttonDown.listeners.reverse()
        vb.mouse.dragged.listeners.reverse()

        extend {

            drawer.clear(ColorRGBa.BLACK)

            drawer.isolatedWithTarget(rt) {
                drawer.image(at, currentIdx)
            }

            currentIdx++
            calculateCentroids()

            if (!settings.cameraView){
                drawer.image(rt.colorBuffer(0))

                drawer.isolatedWithTarget(rt2) {
                    drawer.clear(ColorRGBa.TRANSPARENT)

                    drawer.rectangles {
                        clusters.forEachIndexed  { i, centroids ->
                            val r = Rectangle(i.toDouble() / 45.0 * width, 350.0 + 25.0, width / 45.0, 150.0)
                            centroids.forEachIndexed { index, color ->
                                this.fill = color.toRGBa().toSRGB()
                                this.stroke = null
                                this.rectangle(r.corner + Vector2(0.0, r.height + (index / 6.0) * 140.0), r.width, 140.0 / 6.0)
                            }
                        }
                    }
                }

                fb.blend = 1.0 - settings.smoothingAmt
                fb.apply(rt2.colorBuffer(0), cb)

                drawer.image(cb)

                drawer.rectangles {
                    repeat(45) {
                        val r = Rectangle(it.toDouble() / 45.0 * width, 350.0 + 25.0, width / 45.0, 150.0)
                        this.stroke = ColorRGBa.WHITE.opacify(0.2)
                        this.strokeWeight = 0.1
                        this.fill = null
                        this.rectangle(r)
                    }
                }
            }

            vb.draw()


        }
    }
}
