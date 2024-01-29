import org.openrndr.application
import kotlinx.coroutines.runBlocking
import lib.*
import okhttp3.*
import org.openrndr.color.ColorLABa
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.drawImage
import org.openrndr.extra.color.statistics.deltaE76
import org.openrndr.extra.compositor.*
import org.openrndr.extra.fx.distort.RectangularToPolar
import org.openrndr.extra.shapes.grid
import org.openrndr.extra.timer.repeat
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.launch
import org.openrndr.math.IntVector2
import org.openrndr.shape.*
import org.w3c.dom.events.Event
import java.io.File
import java.io.IOException

fun main() = application {
    configure {
        width = 640
        height = 640
    }
    program {

        extend(ScreenRecorder()) {
            frameSkip = 600
        }

        class Auth: Authenticator {
            override fun authenticate(route: Route?, response: Response): Request {
                return runBlocking {
                     response.request.newBuilder().header("Authorization", Credentials.basic("cms-friends", "Worldwide")).build()
                }
            }
        }

        class Intercept: Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder().addHeader("Connection", "close").build();
                return chain.proceed(request);
            }

        }

        val client = OkHttpClient().newBuilder()
            .authenticator(Auth())
            .addNetworkInterceptor(Intercept())
            .build()

        val url = "http://16115-11.asi.schreder-cms.com:8081/cgi-bin/viewer/video.jpg"

        val request = Request.Builder().url(url).build()

        val cb = colorBuffer(width, height)
        val roi = cb.createEquivalent().apply { flipV = true }

        val topPositions = mutableListOf<Pair<IntVector2, ColorLABa>>()
        var topColors: List<ColorRGBa> = listOf()

        val k = 5

        var outline: ShapeContour? = null
        val ptr = RectangularToPolar()

        var i = 0

        class CallBackAction: Callback {

            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                launch {

                    if (response.isSuccessful) {
                        i++

                        val inputStream = response.body?.byteStream()

                        inputStream?.let {
                            val cb0 = ColorBuffer.fromStream(inputStream)
                            cb0.copyTo(cb, sourceRectangle = cb0.bounds.toInt(), targetRectangle = drawer.bounds.toInt())
                        }


                        if (i < 3) {
                            outline = computeContours(cb).maxBy { it.bounds.area }.removeLoops()
                        }

                        val temp = drawImage(width, height) {
                            drawer.clear(ColorRGBa.TRANSPARENT)
                            compose {
                                draw { drawer.image(cb) }
                                mask { drawer.shape(outline!!.shape) }
                            }.draw(drawer)
                        }

                        ptr.apply(temp, roi)
                        temp.destroy()

                        val shad = roi.shadow.apply { download() }

                        val kpp = kmPP(shad, k = k)
                        val kMeans = kMeansClustering(shad, kpp)

                        val histograms = kMeans.values.map { calcRGBHistogram(it.filter { c -> c.second.alpha != 0.0 && c.second.l > 0.1 }.map { c -> c.second.toRGBa().toSRGB() }) }
                        topColors = histograms.map { it.sortedColors().first().first }.sortedBy { it.luminance }

                        val colorPositions = kMeans.values.flatten()

                        topPositions.clear()
                        topColors.forEach  { tc ->
                            val c = colorPositions.minByOrNull { it.second.toRGBa().toSRGB().deltaE76(tc) }
                            if (c != null && !topPositions.contains(c)) topPositions.add(c)
                        }

                        roi.saveToFile(File("data/video-frames-cms/$i.png"))

                    }

                }

            }

        }

        val cba = CallBackAction()

        repeat(5.0, initialDelayInSeconds = 3.0) {
            client.newCall(request).enqueue(cba)
        }


        extend {

            drawer.image(roi)

            val grid = drawer.bounds.scaledBy(1.0, 0.16, 0.0, 1.0).grid(topPositions.size, 1).flatten()

            val taken = mutableListOf<Int>()

            grid.forEachIndexed { j, it ->

                drawer.stroke = ColorRGBa.WHITE
                val c = it

                val posColor = topPositions.minByOrNull { it.first.vector2.distanceTo(c.center) }
                val idx = topPositions.indexOf(posColor)


                if (posColor != null && !taken.contains(idx)) {
                    taken.add(idx)
                    val (pos, color) = posColor

                    drawer.strokeWeight = 0.15
                    drawer.lineSegment(pos.vector2, c.center)

                    drawer.stroke = ColorRGBa.WHITE
                    drawer.strokeWeight = 1.0
                    drawer.fill = color.toRGBa().toSRGB()
                    drawer.circle(pos.vector2, 10.0)

                    drawer.stroke = ColorRGBa.WHITE
                    drawer.rectangle(c)
                }

            }


        }
    }
}
