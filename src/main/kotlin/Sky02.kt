import lib.calcRGBHistogram
import lib.kMeansClustering
import lib.kmPP
import org.openrndr.application
import org.openrndr.color.ColorLABa
import org.openrndr.color.ColorRGBa
import java.io.File
import org.openrndr.draw.*
import org.openrndr.drawImage
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.shapes.grid
import org.openrndr.math.IntVector2
import org.openrndr.shape.Rectangle

fun main() = application {
    configure {
        width = 1440
        height = 864
    }
    program {

        val files = File("data/video-frames/").listFiles()!!.take(100)
        val frames = files.filter { it.isFile && it.extension == "jpg" }.sortedBy { it.nameWithoutExtension.toInt() }.mapIndexed { i, it ->
            println("$i / ${files.size}")
            val img = loadImage(it)

            drawImage(720, 720) {
                drawer.clear(ColorRGBa.TRANSPARENT)
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                        vec2 texCoord = c_boundsPosition.xy;
                        texCoord.y = 1.0 - texCoord.y;
                        vec2 size = textureSize(p_image, 0);
                        texCoord.x /= size.x/size.y;
                      //  texCoord.x += 0.24;
                        texCoord.y += 0.025;
                        
                        vec2 ogtexCoord = texCoord;
                        
                        texCoord *= 0.9;
                        texCoord += ogtexCoord * 0.05;
                        x_fill = texture(p_image, texCoord);
                    """
                    parameter("image", img)
                }
                drawer.stroke = null
                drawer.fill = ColorRGBa.WHITE
                drawer.circle(360.0, 360.0, 360.0)
            }
        }
        val at = arrayTexture(frames[0].width, frames[0].height, frames.size)
        frames.forEachIndexed { i, it -> it.copyTo(at, i) }

        val k = 5
        val res = 5


        var allColors = mutableListOf<ColorRGBa>()
        var topColors: List<ColorRGBa>
        var topPositions = mutableListOf<Pair<IntVector2, ColorLABa>>()

        val cb = colorBuffer(at.width, at.height)



        val grid =
            Rectangle(0.0, height - 144.0, width / 2.0, 144.0)
                .grid(130.0, 130.0, 0.0, 0.0, 10.0, 10.0)
                .flatten()

        val slitscan = Rectangle(720.0, 0.0, 720.0, height * 1.0).grid(k, frames.size).flatten()

        extend {

            val i = frameCount.coerceAtMost(frames.lastIndex)

            if (i == frames.count()) application.exit()

            println("changed")

            at.copyTo(i, cb)

            val shad = cb.shadow.apply { download() }

            val centroids = kmPP(shad)
            val clusters = kMeansClustering(shad, centroids)

            val histograms = clusters.values.map { calcRGBHistogram(it.filter { c -> c.second.alpha != 0.0 && c.second.l > 0.1 }.map { c -> c.second.toRGBa().toSRGB() }) }
            topColors = histograms.map { it.sortedColors().first().first }.sortedBy { it.luminance }

            allColors.addAll(topColors)

            val colorPositions = clusters.values.flatten()

            topPositions.clear()
            topColors.map  { tc ->
                val c = colorPositions.minByOrNull { it.second.toRGBa().toSRGB().deltaE76(tc) }
                if (c != null) topPositions.add(c)
            }

            drawer.image(cb)

            grid.take(topColors.size).forEachIndexed { j, it ->

                drawer.stroke = ColorRGBa.WHITE

                val posColor = topPositions.getOrNull(j)

                if (posColor != null) {
                    val (pos, color) = posColor

                    drawer.strokeWeight = 0.1
                    drawer.lineSegment(pos.vector2, it.center)

                    drawer.stroke = ColorRGBa.WHITE
                    drawer.strokeWeight = 2.0
                    drawer.fill = color.toRGBa().toSRGB()
                    drawer.circle(pos.vector2, 10.0)
                }

                drawer.fill = topColors.getOrNull(j)
                drawer.stroke = ColorRGBa.WHITE
                drawer.rectangle(it)

            }



            drawer.rectangles {
                for ((j, rect) in slitscan.take(allColors.size).withIndex()) {
                    this.stroke = null
                    this.fill = allColors[j]
                    this.rectangle(rect)
                }
            }


        }
    }
}
