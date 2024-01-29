import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.color.statistics.RGBHistogram
import org.openrndr.extra.color.statistics.calculateHistogramRGB
import org.openrndr.extra.shapes.grid
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.ffmpeg.VideoPlayerConfiguration
import org.openrndr.ffmpeg.loadVideo
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import kotlin.math.pow

fun main() = application {
    configure {
        width = 1280
        height = 960
    }

    program {

        val config = VideoPlayerConfiguration().apply {
            this.allowFrameSkipping = false
        }
        val cb = colorBuffer(width, height, type = ColorType.FLOAT32)
        val source = loadVideo("data/sky.mp4", configuration = config).apply { play() }

        var sortedColors = listOf<Pair<ColorRGBa, Double>>()

        source.newFrame.listen {
            it.frame.copyTo(cb)

            val histogram = calculateHistogramRGB(cb, 16, weighting = {
                (r + g + b).pow(2.4)
            })
            sortedColors = histogram.sortedColors()
        }
        source.ended.listen {
            source.restart()
            source.play()
        }

        val grid =
            Rectangle(0.0, height - 240.0, width * 1.0, 240.0)
                .grid(240.0, 240.0, 0.0, 0.0, 10.0, 10.0)
                .flatten()

        /*extend(ScreenRecorder()) {
            this.frameRate = 30
        }*/
        extend {
            source.draw(drawer, false)

            drawer.image(cb.apply { flipV = true })

            grid.take(sortedColors.size).forEachIndexed { i, it ->
                drawer.fill = sortedColors[i].first
                drawer.rectangle(it)
            }
        }
    }
}