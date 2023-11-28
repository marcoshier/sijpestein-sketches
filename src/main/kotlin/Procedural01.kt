import net.e175.klaus.solarpositioning.DeltaT
import net.e175.klaus.solarpositioning.SPA
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.color.mix
import org.openrndr.draw.*
import org.openrndr.extra.camera.Orbital
import org.openrndr.extra.color.presets.OLIVE
import org.openrndr.extra.color.presets.ORANGE
import org.openrndr.extra.meshgenerators.sphereMesh
import org.openrndr.extra.olive.oliveProgram
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.*
import org.openrndr.shape.Rectangle
import java.time.ZonedDateTime

fun main() = application {
    configure {
        width = 1280
        height = 720
    }
    program {

        extend(ScreenRecorder())

        val ss = shadeStyle {
            fragmentPreamble = """
                #define ATM_NUM_SCATTERING_STEPS 24u
                #define ATM_NUM_TRANSMITTANCE_STEPS 16u
                
                const float kAtmRadiusMin = 6360.0;
                const float kAtmRadiusMax = 6420.0;
                const float kKilometersToMeters = 1000.0;

                const float kAtmRayHeightScale = 1.0 / 8.0;
                const float kAtmMieHeightScale = 1.0 / 1.2;

                // http://www.iup.physik.uni-bremen.de/gruppen/molspec/databases/referencespectra/o3spectra2011/index.html
                // Version 22.07.2013: Fast Fourier Transform Filter applied to the initial data in the region 213.33 -317 nm
                const float kAtmOznCrossSection_293K_680nm = 1.36820899679147; // * 10^-25 m^2 * molecule^-1
                const float kAtmOznCrossSection_293K_550nm = 3.31405330400124; // * 10^-25 m^2 * molecule^-1
                const float kAtmOznCrossSection_293K_440nm = 0.13601728252538; // * 10^-25 m^2 * molecule^-1

                // https://en.wikipedia.org/wiki/Number_density
                const float kAtmAirNumberDensity_293K = 2.504; // * 10^25 molecule * m^-3

                // Choose 6 parts per million which is within 15 parts per million reported here:
                // https://ozonewatch.gsfc.nasa.gov/facts/ozone.html
                const float kAtmOznConcentration = 6.0e-6;

                // Compute ozone absorption coefficients
                const float kAtmOznNumberDensity_293K = kAtmAirNumberDensity_293K * kAtmOznConcentration;
                const float kAtmOznAbsorption_293K_680nm = kAtmOznCrossSection_293K_680nm * kAtmOznNumberDensity_293K;
                const float kAtmOznAbsorption_293K_550nm = kAtmOznCrossSection_293K_550nm * kAtmOznNumberDensity_293K;
                const float kAtmOznAbsorption_293K_440nm = kAtmOznCrossSection_293K_440nm * kAtmOznNumberDensity_293K;

                // 
                // Precomputed Rayleigh scattering coefficients for wavelength L using the following formula :
                // ScatteringCoeff(L) = (8.0*pi/3.0) * (n^2.0 - 1.0)^2.0 * ((6.0+3.0*p) / (6.0-7.0*p)) / (L^4.0 * N)
                // where
                // n - refractive index of the air (1.0003) https://en.wikipedia.org/wiki/Refractive_index
                // p - air depolarization factor (0.035) 
                // N - air number density under NTP : (2.504 * 10^25 molecule * m^-3) 
                // L - wavelength for which scattering coefficient is computed
                //
                // See "Rayleigh-scattering calculations for the terrestrial atmosphere" by A.Bucholtz for reference
                // 
                const float kAtmRayScattering_680nm =  5.8e-6;
                const float kAtmRayScattering_550nm = 13.6e-6;
                const float kAtmRayScattering_440nm = 33.1e-6;

                const float kAtmMieScattering = 2.0e-6;
                const float kAtmMieExtinction = kAtmMieScattering * 1.11;

                // cosine of horizon angle at top of the atmosphere
                const float kAtmHorizonSinMin = kAtmRadiusMin / kAtmRadiusMax;
                const float kAtmHorizonCosMin = -sqrt(max(1.0 - kAtmHorizonSinMin * kAtmHorizonSinMin, 0.0));

                // Luminance recovered from "Reference Solar Spectral Irradiance: ASTM G-173"
                // (http://rredc.nrel.gov/solar/spectra/am1.5/ASTMG173/ASTMG173.html)
                // Scaled by exp2(-16.0);
                const float kAtmRadianceResponseToRGB_Lum = 2.225477;


                // Coefficients to convert 3 spectrum samples to linear sRGB
                // computed using formula 2 from Eric Bruneton's paper 
                // "A Qualitative and Quantitative Evaluation of 8 Clear Sky Models"
                // Actual values are scaled by exp2(-16.0);
                const float kAtmRadianceResponseToRGB_680nm = 2.795308;
                const float kAtmRadianceResponseToRGB_550nm = 2.345072;
                const float kAtmRadianceResponseToRGB_440nm = 2.079126;

                const float kPi = 3.14159265359;
                const float kOneOver4Pi = 1.0 / (4.0 * kPi);

                float Sqrt(float x)
                {
                    return sqrt(max(0.0, x));
                }

                float phaseR(float VoL)
                {
                    return kOneOver4Pi * 0.75 * (1.0 + VoL * VoL);
                }

                float phaseM_HG(float VoL, float G)
                {
                    float A = max(0.0, 1.0 + G * (G - 2.0 * VoL));
                    float D = 1.0 / Sqrt(A * A * A);
                    return (1.0 - G * G) * kOneOver4Pi * D;
                }

                float phaseM_CS(float VoL, float G)
                {
                    return 1.5 * (1.0 + VoL * VoL) * phaseM_HG(VoL, G) / (2.0 + G * G);
                }

                float AtmHorizonCos(float R)
                {
                    float SinH = kAtmRadiusMin / R;
                    float CosH = -Sqrt(1.0 - SinH * SinH);
                    return CosH;
                }

                float AtmIntersectTop(float R, float V)
                {
                    float RMaxOverR = kAtmRadiusMax / R;
                    return -R * (V - sqrt(V * V - 1.0 + RMaxOverR * RMaxOverR));
                }
                // V - cosine of the angle between view direction and zenith
                // R - radius at starting point
                vec3 OpticalLengthStep(float R, float V, float Di)
                {
                    // Re-compute radius at distance Di using cosine theorem
                    float Ri = Sqrt((Di + 2.0 * V * R) * Di + R * R);
                    float Hi = Ri - kAtmRadiusMin;
                    
                    // Standard Rayleigh / Mie density profiles
                    float RayDensity = exp(-Hi * kAtmRayHeightScale);
                    float MieDensity = exp(-Hi * kAtmMieHeightScale);
                    
                    // Piecewise linear approximation of the ozone profile from (Page 10) :
                    // ftp://es-ee.tor.ec.gc.ca/pub/ftpcm/!%20for%20Jacob/Introduction%20to%20atmospheric%20chemistry.pdf
                    // Density linearly increases from 0 at 15Km to 1.0 at 25Km and decreases back to 0.0 at 40.0Km
                    float OznDensity = Hi < 25.0 ? clamp( Hi / 15.0 - 2.0 / 3.0, 0.0, 1.0)
                                                 : clamp(-Hi / 15.0 + 8.0 / 3.0, 0.0, 1.0);
                    
                    return vec3(RayDensity, MieDensity, OznDensity * RayDensity);
                }

                vec3 OpticalLength(float R, float V, uint NumSteps)
                {    
                    // Early our with infinite to optical length below horizon to make transmittance -> 0.0
                    if (V <= AtmHorizonCos(R))
                    {
                        return vec3(1.0e9);
                    }
                    float MaxDistance = AtmIntersectTop(R, V);
                    float StpDistance = MaxDistance / float(NumSteps);
                    
                    vec3 OptLen = vec3(0.0);
                    
                    OptLen += OpticalLengthStep(R, V, 0.0);
                    OptLen += OpticalLengthStep(R, V, MaxDistance);
                    OptLen *= 0.5;
                    
                    for (uint iStep = 1u; iStep < NumSteps; ++iStep)
                    {
                        OptLen += OpticalLengthStep(R, V, float(iStep) * StpDistance);
                    }
                    return OptLen * StpDistance;
                }

                vec3 OznAbsorption()
                {
                	vec3 Absorption;
                    Absorption.r = kAtmOznAbsorption_293K_680nm;
                    Absorption.g = kAtmOznAbsorption_293K_550nm;
                    Absorption.b = kAtmOznAbsorption_293K_440nm;
                #if ATM_OZONE_ABSORPTION
                    return Absorption * kKilometersToMeters;
                #else
                    return vec3(0.0);
                #endif
                }

                vec3 RayScattering()
                {
                    vec3 Scattering;
                    Scattering.r = kAtmRayScattering_680nm;
                    Scattering.g = kAtmRayScattering_550nm;
                    Scattering.b = kAtmRayScattering_440nm;
                    return Scattering * kKilometersToMeters;
                }

                vec3 MieExtinction()
                {
                    return vec3(kAtmMieExtinction * kKilometersToMeters);
                }

                vec3 MieScattering()
                {
                    return vec3(kAtmMieScattering * kKilometersToMeters);
                }

                vec3 Transmittance(vec3 OptLen, float UseOzoneAbsorption)
                {
                    vec3 OznOptDepth = OznAbsorption() * OptLen.b * UseOzoneAbsorption;
                    vec3 RayOptDepth = RayScattering() * OptLen.r;
                    vec3 MieOptDepth = MieScattering() * OptLen.g;
                    return exp(-(RayOptDepth + MieOptDepth + OznOptDepth));
                }

                // Parameters for ScatteringStep / Scattering functions
                // R 		- radius at starting point
                
                // V 		- cosine of the angle between view direction and zenith
                // L 		- cosine of the angle between direction to the light source and zenith
                // VoL 		- cosine of the angle between view direction and direction to the light source
                
                // Di  		- current distance in direction defined by V
                // OptLenV 	- optical length from a starting to the atmosphere's top
                // UseOzoneAbsorption - 1.0 or 0.0 to enable or disbale ozone absorption
                void ScatteringStep(float R, float V, float L, float VoL, float Di, vec3 OptLenV, float UseOzoneAbsorption, out vec3 RayS, out vec3 MieS)
                {
                	float Ri = Sqrt((Di + 2.0 * V * R) * Di + R * R);
                    float Vi = (R * V + Di) / Ri;
                    float Li = (R * L + Di * L) / Ri;
                    
                    if (Li > AtmHorizonCos(Ri))
                    {
                        // Opitcal length from a current point to the atmoshere bound 
                        // in view direction and in direction to the light source
                        vec3 OptLenVi = OpticalLength(Ri, Vi, ATM_NUM_TRANSMITTANCE_STEPS);
                        vec3 OptLenLi = OpticalLength(Ri, Li, ATM_NUM_TRANSMITTANCE_STEPS);
                        
                        // Compute total optical length of the path and compute transmittance from it
                        vec3 Ti = Transmittance(max(OptLenV - OptLenVi, 0.0) + OptLenLi, UseOzoneAbsorption);
                        
                        float Hi = Ri - R;
                        
                        // Multiply by corresponding particle density
                        RayS = Ti * exp(-Hi * kAtmRayHeightScale);
                        MieS = Ti * exp(-Hi * kAtmMieHeightScale);
                    }
                }


                void Scattering(float R, float V, float L, float VoL, float UseOzoneAbsorption, out vec3 Scattering)
                {
                    vec3 RayS = vec3(0.0);
                    vec3 MieS = vec3(0.0);
                    
                    float CosH = AtmHorizonCos(R);

                    V = max(V, CosH);
                    if (V >= CosH)
                    {
                        uint NumSteps = ATM_NUM_SCATTERING_STEPS;
                        float MaxDistance = AtmIntersectTop(R, V);
                    	float StpDistance = MaxDistance / float(NumSteps);
                        
                        vec3 OptLenV = OpticalLength(R, V, ATM_NUM_TRANSMITTANCE_STEPS);
                        
                        vec3 RaySi;
                        vec3 MieSi;
                        
                        ScatteringStep(R, V, L, VoL, 0.0, OptLenV, UseOzoneAbsorption, RaySi, MieSi);
                        RayS = RaySi;
                        MieS = RaySi;
                        
                        ScatteringStep(R, V, L, VoL, MaxDistance, OptLenV, UseOzoneAbsorption, RaySi, MieSi);
                        RayS = (RayS + RaySi) * 0.5;
                        MieS = (MieS + MieSi) * 0.5;
                        
                        for (uint iStep = 1u; iStep < NumSteps; ++iStep)
                    	{
                            ScatteringStep(R, V, L, VoL, float(iStep) * StpDistance, OptLenV, UseOzoneAbsorption, RaySi, MieSi);
                        	RayS += RaySi;
                            MieS += MieSi;
                    	}

                        RayS *= StpDistance * RayScattering() * phaseR(VoL);
                        MieS *= StpDistance * MieScattering() * phaseM_CS(VoL, 0.76);
                        
                        const float kSunAngularRadius32Min33Sec = 0.00473420559;
                        float ToEdge = clamp((1.0 - VoL) / kSunAngularRadius32Min33Sec, 0.0, 1.0);
                        
                        float CosAngle1 = cos(ToEdge * kPi * 0.5);
                        float CosAngle4 = Sqrt(1.0 - ToEdge * ToEdge);
                        float Mask = 1.0
                            #if 0
                                   - ((ToEdge == 1.0) ? 1.0 : 0.0);
                            #else
                                   - ((ToEdge >= 0.8) ? smoothstep(0.8, 1.0, ToEdge) : 0.0);
                            #endif
                		
                        // Limb Darkening model from http://www.physics.hmc.edu/faculty/esin/a101/limbdarkening.pdf
                        // See Formula 1. 
                        // Coefficients for wavelengths close to (680 550 440) nm are from Table 2 (PS column)
                        vec3 LimbDarkening = Mask * pow(vec3(1.0 - 1.0 * (1.0 - CosAngle1)), vec3(0.420, 0.503, 0.652));
                        
                        Scattering = (RayS + MieS) + Transmittance(OptLenV, UseOzoneAbsorption) * LimbDarkening;
                    }
                }

                vec3 sRGMGamma(vec3 color)
                {
                    vec3 x = color * 12.92;
                    vec3 y = 1.055 * pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.4)) - 0.055;
                	color.r = color.r < 0.0031308 ? x.x : y.x;
                    color.g = color.g < 0.0031308 ? x.y : y.y;
                    color.b = color.b < 0.0031308 ? x.z : y.z;
                    return color;
                }

                // John Hable's tonemapping function from presentation "Uncharted 2 HDR Lighting", Page 142-143
                vec3 ToneMap_Uncharted2(vec3 color)
                {
                    float A = 0.15; // 0.22
                	float B = 0.50; // 0.30
                	float C = 0.10;
                	float D = 0.20;
                	float E = 0.02; // 0.01
                	float F = 0.30;
                	float W = 11.2;
                    
                    vec4 x = vec4(color, W);
                    x = ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
                	return sRGMGamma(x.xyz / x.w);
                }

                vec3 UVToEquirectCoord(float U, float V, float MinCos)
                {
                    float Phi = kPi - V * kPi;
                    float Theta = U * 2.0 * kPi;
                    
                    vec3 Dir = vec3(cos(Theta), 0.0, sin(Theta));
                	Dir.y   = clamp(cos(Phi), MinCos, 1.0);
                	Dir.xz *= Sqrt(1.0 - Dir.y * Dir.y);
                    return Dir;
                }
                
                vec3 sunPosToEquirectCoord(float MinCos) {
                    vec3 Dir = vec3(cos(p_theta), 0.0, sin(p_theta));
                	Dir.y   = cos(p_phi);
                	Dir.xz *= Sqrt(1.0 - Dir.y * Dir.y);
                    return Dir;
                }
               
               

                float sdCircle( in vec2 p, in float r ) 
                {
                    return length(p)-r;
                }

            """.trimIndent()

            fragmentTransform = """
                vec2 uv = c_boundsPosition.xy;
                float U = uv.x;
                float V = 1.0 - uv.y;
                
                float r = kAtmRadiusMin + 0.1;
                float cosH = AtmHorizonCos(r) - 0.01;
                
                vec3 VDir = UVToEquirectCoord(U, V, -1.0);
                vec3 LDir = sunPosToEquirectCoord(cosH);
                
                float VoL = dot(LDir, VDir);
        
                V = VDir.y;
                float L = LDir.y;

                vec3 Result = vec3(0.0);
                Scattering(r, V, L, VoL, 1.0, Result);
        
                float Exposure = exp2(5.0);
        
                Result *= Exposure;
        
            
                Result.x *= kAtmRadianceResponseToRGB_680nm;
                Result.y *= kAtmRadianceResponseToRGB_550nm;
                Result.z *= kAtmRadianceResponseToRGB_440nm;
                
                Result = ToneMap_Uncharted2(Result);
                
                x_fill = vec4(Result, 1.0);
            """.trimIndent()
        }

        val mg = sphereMesh(64, 64, 20.0)

        val globe0 = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        val globe1 = renderTarget(300, 300) {
            colorBuffer()
            depthBuffer()
        }

        val texture = renderTarget(width, height) {
            colorBuffer()
            depthBuffer()
        }

        val o = Orbital()
        extend(o) {
        }
        var time = ZonedDateTime.now()
        extend {
            time = time.plusSeconds(120)

            val denHaagLat = 52.078663
            val denHaagLon = 4.288788

            val sunPos = SPA.calculateSolarPosition(
                time,
                denHaagLat,
                denHaagLon,
                1.0,
                DeltaT.estimate(time.toLocalDate())
            )

            ss.parameter("theta", Math.toRadians(sunPos.azimuth))
            ss.parameter("phi", Math.toRadians(sunPos.zenithAngle))

            val yPos = (sunPos.zenithAngle / 90.0) * height / 2.0 - 50.0
            val regionRectangle = Rectangle(((sunPos.azimuth / 180.0) * width / 2.0 - 350.0).coerceIn(0.0, width - 300.0), yPos, 300.0, 100.0)

            drawer.isolatedWithTarget(texture) {
                drawer.clear(ColorRGBa.BLACK)
                drawer.defaults()
                drawer.shadeStyle = ss

                drawer.stroke = null
                drawer.rectangle(drawer.bounds)
                drawer.shadeStyle = null

                drawer.strokeWeight = 0.8
                drawer.stroke = ColorRGBa.RED
                drawer.fill = null

                drawer.rectangle(0.0, yPos, width * 1.0, 100.0)

                drawer.stroke = ColorRGBa.BLUE
                drawer.rectangle(regionRectangle)
            }


            fun globe() {
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = """
                    vec2 uv = va_texCoord0;
                    uv.x -= 0.5;
                    x_fill = texture(p_texture, uv);
                """.trimIndent()
                    parameter("texture", texture.colorBuffer(0))
                }

                drawer.view = o.camera.viewMatrix()

                drawer.vertexBuffer(mg, DrawPrimitive.TRIANGLES)
                drawer.shadeStyle = null

                drawer.fill = ColorRGBa.WHITE.opacify(0.1)
                drawer.vertexBuffer(mg, DrawPrimitive.LINE_LOOP)
            }

            drawer.isolatedWithTarget(globe0) {
                drawer.clear(ColorRGBa.TRANSPARENT)
                o.camera.setView(Vector3.ZERO, Spherical(sunPos.azimuth, sunPos.zenithAngle, 100.0), 50.0)

                globe()
                drawer.defaults()
            }

            drawer.isolated {
                drawer.defaults()
                drawer.translate(-450.0, -150.0)
                drawer.image(globe0.colorBuffer(0))
            }


            drawer.isolatedWithTarget(globe1) {
                drawer.depthWrite = true
                drawer.clear(ColorRGBa.TRANSPARENT)
                o.camera.setView(Vector3.ZERO, Spherical(sunPos.azimuth, sunPos.zenithAngle, -1.0), 50.0)

                globe()
            }

            drawer.isolated {
                drawer.defaults()
                drawer.translate(40.0, height / 2.0 + 30.0)
                drawer.image(globe1.colorBuffer(0))
            }

            drawer.defaults()
            drawer.fill = ColorRGBa.WHITE
            drawer.text(time.toString(), 20.0, 30.0)

            val destRect = drawer.bounds.scaledBy(0.65, 0.0, 0.0).movedBy(Vector2(400.0, 85.0))
            drawer.isolated {
                drawer.drawStyle.clip = destRect.scaledBy(1.0, 0.5, 0.0, 0.0)
                drawer.image(texture.colorBuffer(0), drawer.bounds, destRect)
                drawer.drawStyle.clip = null
            }

            drawer.isolated {
                drawer.stroke = ColorRGBa.BLUE
                val v =  map(60.0, 130.0, 0.0, 1.0, sunPos.zenithAngle)
                drawer.fill = mix(ColorRGBa.TRANSPARENT, ColorRGBa.ORANGE, v)
                val gradientRect = Rectangle(destRect.x, 400.0, regionRectangle.width, regionRectangle.height).scaledBy(2.75, 0.0, 0.0)
                println(v)
                drawer.image(texture.colorBuffer(0), regionRectangle, gradientRect)
                drawer.rectangle(gradientRect)
            }

        }
    }
}