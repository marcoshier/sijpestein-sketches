import org.openrndr.application
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.olive.oliveProgram

fun main() = application {
    configure { }
    oliveProgram {
        extend {

            val ss = shadeStyle {
                fragmentPreamble = """
                   
                float sdCircle( in vec2 p, in float r ) 
                {
                    return length(p)-r;
                }
                """.trimIndent()
                fragmentTransform = """
	                vec2 p = c_boundsPosition.xy - 0.5;

                    float d = sdCircle(p, 0.4);
                    vec4 c =  mix(vec4(0.0), vec4(1.0), 1.0-smoothstep(0.0,0.02,abs(d)));
                
                    x_fill = c; 
                """.trimIndent()
            }

            drawer.shadeStyle = ss
            drawer.rectangle(drawer.bounds)

        }

    }
}