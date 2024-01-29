import org.openrndr.application
import org.openrndr.draw.ColorType
import org.openrndr.draw.colorBuffer
import org.openrndr.ffmpeg.VideoPlayerConfiguration
import org.openrndr.ffmpeg.loadVideo
import java.io.File

fun main() = application {
    configure {
        width = 2496
        height = 2496
    }
    program {

        val config = VideoPlayerConfiguration().apply { allowFrameSkipping = false }
        val source = loadVideo("offline-data/V0230024.MP4", configuration = config).apply { play() }

        var r = 0
        source.newFrame.listen {
            it.frame.saveToFile(File("data/video-frames/$r.jpg"))
            r++
        }

        source.ended.listen {
            println("ended")
            application.exit()
        }

        extend {
            source.draw(drawer)

        }
    }
}