import lib.RGBHistogram
import lib.computeContours
import org.openrndr.Program
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.color.presets.PURPLE
import org.openrndr.extra.color.spaces.ColorOKLCHa
import org.openrndr.extra.color.spaces.toOKLCHa
import org.openrndr.extra.color.statistics.calculateHistogramOKLAB
import org.openrndr.extra.compositor.*
import org.openrndr.shape.Rectangle
import org.openrndr.shape.removeLoops
import java.io.File

fun main() = application {
    configure {
        width = 640
        height = 640
    }
    program {

        val files = File("data/video-frames-cms").listFiles()!!
            .filter { it.isFile }
            .sortedBy { it.nameWithoutExtension.toInt() }
            .take(400)

        val at = preprocess(files)

        val currentTargets = (0..3).map {
            renderTarget(at.width, at.height) { colorBuffer() }
        }

        var currentIdx = 0


        extend {

            currentIdx++

            ((currentIdx..currentIdx + 3)).forEachIndexed { i, idx ->
                drawer.isolatedWithTarget(currentTargets[i]) {
                    drawer.defaults()
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    drawer.image(at, idx)
                }
            }

            drawer.image(at, currentIdx + 3)


            (0..45).forEach {
                val rX = it.toDouble() / 45.0 * width
                val rWidth = width / 45.0
                val rect = Rectangle(
                    rX - (rWidth / 2.0),
                    300.0,
                    width / 45.0 + (rWidth / 2.0),
                    150.0)

                drawer.fill = null
                drawer.strokeWeight = 0.02
                drawer.stroke = ColorRGBa.WHITE.opacify(0.5)
                drawer.rectangle(rect)

                val dc = getDominantColor(currentTargets.map { it.colorBuffer(0) }, rect)

                drawer.stroke = null
                drawer.fill = dc.toRGBa()
                drawer.rectangle(rX, rect.y + rect.height, rWidth, height - (rect.y + rect.height))
            }


        }
    }
}

fun getDominantColor(images: List<ColorBuffer>, cropRect: Rectangle): ColorOKLCHa {

    val colorsToWeights = images.mapIndexed { i, it ->
        val cropped = it.crop(cropRect.toInt())
        val h = calculateHistogramOKLAB(cropped)

        h.sortedColors().first().first to ((i + 1) / (images.size + 1.0))
    }

    //println(colorsToWeights.map { it.first })

    val weightedColors = colorsToWeights.map { it.first.times(it.second) }
    val r = weightedColors.reduce { acc, new -> acc + new }

    return r / colorsToWeights.sumOf { it.second }
}



fun Program.preprocess(files: List<File>): ArrayTexture {
    val at = arrayTexture(640, 640, files.size)
    val shape = computeContours(loadImage(files[0])).maxBy { it.bounds.area }.removeLoops().shape

    files.forEachIndexed { i, file ->
        println("$i / ${files.size}")

        val img = loadImage(file)
        val c = compose {
            draw { drawer.image(img) }
            mask { drawer.shape(shape) }
        }
        c.draw(drawer)
        c.result.copyTo(at, i)

        c.result.destroy()
        img.destroy()
    }

    return at
}