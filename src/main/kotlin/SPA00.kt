import net.e175.klaus.solarpositioning.DeltaT
import net.e175.klaus.solarpositioning.SPA
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.camera.Orbital
import org.openrndr.extra.meshgenerators.*
import org.openrndr.ffmpeg.loadVideo
import org.openrndr.math.Spherical
import org.openrndr.math.Vector3
import java.time.ZonedDateTime
import kotlin.math.max

fun main() = application {
    configure {
        width = 1280
        height = 720
    }
    program {

        val video = loadVideo("offline-data/output.mp4")
        val cb = colorBuffer(1280, 720)
        video.play()

        video.ended.listen {
            video.restart()
        }

        video.newFrame.listen {
            it.frame.copyTo(cb)
        }

        val mg = buildTriangleMesh {
            hemisphere(36, 18, 20.0)
        }

        extend(Orbital()) {
            eye = Vector3.UNIT_Z * 30.0
        }
        extend {
            drawer.clear(ColorRGBa.GRAY.shade(0.7))
            video.draw(drawer, blind = true)

            val time = ZonedDateTime.now()
            val sunPos = SPA.calculateSolarPosition(
                time,
                52.078663,
                4.288788,
                1.0,
                DeltaT.estimate(time.toLocalDate())
            )


            val pos = Spherical(sunPos.azimuth, sunPos.zenithAngle, 20.0).cartesian

            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec2 uv = va_texCoord0;
                    uv.x-= 0.5;
                    uv.y = 1.0 - uv.y;
                    x_fill = texture(p_texture, uv);
                """.trimIndent()
                parameter("texture", cb)
            }
            drawer.vertexBuffer(mg, DrawPrimitive.TRIANGLES, vertexCount = mg.vertexCount / 2 - 54)

        }
    }
}

