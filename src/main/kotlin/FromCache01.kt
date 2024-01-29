import lib.calcRGBHistogram
import lib.computeContours
import lib.kMeansClustering
import lib.kmPP
import org.openrndr.application
import org.openrndr.color.ColorLABa
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.drawImage
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.compositor.compose
import org.openrndr.extra.compositor.draw
import org.openrndr.extra.compositor.mask
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.IntVector2
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.difference
import org.openrndr.shape.removeLoops
import java.io.File
import java.nio.ByteBuffer

fun main() = application {
    configure {
        width = 640
        height = 640
    }
    program {

        extend(ScreenRecorder()) {
            maximumFrames = 2048
            quitAfterMaximum = true
        }

        val f = File("data/video-frames-cms").listFiles()!!.filter { it.isFile }.take(2048).sortedBy { it.nameWithoutExtension.toInt() }

        val at = arrayTexture(width, height, f.size)

        f.forEachIndexed { i, file ->
            println("$i / ${f.size}")
            loadImage(file).copyTo(at, i)
        }

        var i = 0

        val outline = computeContours(loadImage(f[0])).maxBy { it.bounds.area }.removeLoops()

        val rt = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        val rt2 = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        val c = compose {
            draw {
                i++
                drawer.image(at, i)
            }
            mask { drawer.shape(outline.shape) }
        }

        val k = 10

        extend {

            println("$frameCount $i")


            drawer.clear(ColorRGBa.BLACK)

            drawer.isolatedWithTarget(rt) {
                c.draw(drawer)
            }

            drawer.image(rt.colorBuffer(0))

            for (j in 0 until 30) {
                val topPositions = mutableListOf<Pair<IntVector2, ColorLABa>>()
                var topColors: List<ColorRGBa> = listOf()

                val r = Rectangle(j.toDouble() / 30.0 * width, 300.0, width / 30.0, 200.0)

                val shad = rt.colorBuffer(0).crop(r.toInt()).shadow.apply { download() }

                val kpp = kmPP(shad, k = 5)
                val kMeans = kMeansClustering(shad, kpp)

                val histograms = kMeans.values.map { calcRGBHistogram(it.filter { c -> c.second.alpha != 0.0 && c.second.l > 0.1 }.map { c -> c.second.toRGBa().toSRGB() }) }
                topColors = histograms.map { it.sortedColors().first().first }.sortedBy { it.luminance }

                val colorPositions = kMeans.values.flatten()

                topPositions.clear()
                topColors.forEach  { tc ->
                    val color = colorPositions.minByOrNull { it.second.toRGBa().toSRGB().deltaE76(tc) }
                    if (color != null && !topPositions.contains(color)) topPositions.add(color)
                }

                drawer.isolatedWithTarget(rt2) {
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    drawer.drawStyle.clip = r
                    drawer.stroke = ColorRGBa.WHITE.opacify(0.2)
                    drawer.strokeWeight = 0.02
                    drawer.fill = null
                    drawer.rectangle(r)


                    for ((index,posColor) in topPositions.withIndex()) {

                        val (pos, color) = posColor

                        drawer.strokeWeight = 0.5
                        drawer.stroke = ColorRGBa.WHITE.opacify(1.0)
                        drawer.fill = color.toRGBa().toSRGB().opacify(1.0)
                        drawer.circle(pos.vector2 + r.corner, 6.0)

                    }
                    drawer.drawStyle.clip = null

                    for ((index, pair) in topPositions.withIndex()) {
                        drawer.fill = pair.second.toRGBa().toSRGB()
                        drawer.stroke = null
                        drawer.rectangle(r.corner + Vector2(0.0, r.height + (index / 5.0) * 140.0), r.width, 140.0 / 5.0)
                    }
                }

                drawer.image(rt2.colorBuffer(0))

            }


        }
    }
}