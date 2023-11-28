#version 450 core
// <primitive-types> (ShadeStyleGLSL.kt)
#define d_vertex_buffer 0
#define d_image 1
#define d_circle 2
#define d_rectangle 3
#define d_font_image_map 4
#define d_expansion 5
#define d_fast_line 6
#define d_mesh_line 7
#define d_point 8
#define d_custom 9
#define d_primitive d_rectangle
// </primitive-types>


uniform float p_theta;
uniform float p_phi;
layout(origin_upper_left) in vec4 gl_FragCoord;

// <drawer-uniforms(true, false)> (ShadeStyleGLSL.kt)
            
layout(shared) uniform ContextBlock {
    uniform mat4 u_modelNormalMatrix;
    uniform mat4 u_modelMatrix;
    uniform mat4 u_viewNormalMatrix;
    uniform mat4 u_viewMatrix;
    uniform mat4 u_projectionMatrix;
    uniform float u_contentScale;
    uniform float u_modelViewScalingFactor;
    uniform vec2 u_viewDimensions;
};
            
// </drawer-uniforms>
in vec3 va_position;
in vec3 va_normal;
in vec2 va_texCoord0;
in vec3 vi_offset;
in vec2 vi_dimensions;
in float vi_rotation;
in vec4 vi_fill;
in vec4 vi_stroke;
in float vi_strokeWeight;


// <transform-varying-in> (ShadeStyleGLSL.kt)
in vec3 v_worldNormal;
in vec3 v_viewNormal;
in vec3 v_worldPosition;
in vec3 v_viewPosition;
in vec4 v_clipPosition;
flat in mat4 v_modelNormalMatrix;
// </transform-varying-in>

out vec4 o_color;

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

flat in int v_instance;
in vec3 v_boundsSize;

    // -- fragmentConstants
    int c_instance = v_instance;
    int c_element = 0;
    vec2 c_screenPosition = gl_FragCoord.xy / u_contentScale;
    float c_contourPosition = 0.0;
    vec3 c_boundsPosition = vec3(va_texCoord0, 0.0);
    vec3 c_boundsSize = v_boundsSize;
    
void main(void) {
    vec4 x_fill = vi_fill;
    vec4 x_stroke = vi_stroke;
    {
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

float Exposure = exp2(4.0);

Result *= Exposure;


Result.x *= kAtmRadianceResponseToRGB_680nm;
Result.y *= kAtmRadianceResponseToRGB_550nm;
Result.z *= kAtmRadianceResponseToRGB_440nm;

Result = ToneMap_Uncharted2(Result);

vec3 voffset = (x_viewMatrix * vec4(i_offset, 1.0)).xyz;
x_viewMatrix = mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0);
vec4 cp = x_projectionMatrix * vec4(voffset, 1.0);
        vec2 sp = cp.xy / cp.w;


x_fill = vec4(Result, 1.0);
    }
    vec2 wd = fwidth(va_texCoord0 - vec2(0.5));
    vec2 d = abs((va_texCoord0 - vec2(0.5)) * 2);

    float irx = smoothstep(0.0, wd.x * 2.5, 1.0-d.x - vi_strokeWeight*2.0/vi_dimensions.x);
    float iry = smoothstep(0.0, wd.y * 2.5, 1.0-d.y - vi_strokeWeight*2.0/vi_dimensions.y);
    float ir = irx*iry;

    vec4 final = vec4(1.0);
    final.rgb = x_fill.rgb * x_fill.a;
    final.a = x_fill.a;

    float sa = (1.0-ir) * x_stroke.a;
    final.rgb = final.rgb * (1.0-sa) + x_stroke.rgb * sa;
    final.a = final.a * (1.0-sa) + sa;

       o_color = final;
}
// -------------
// shade-style-custom:rectangle--1755194756
// created 2023-10-24T15:26:29.127740300
/*
0(431) : error C1503: undefined variable "x_viewMatrix"
0(431) : error C1503: undefined variable "i_offset"
0(432) : error C1503: undefined variable "x_viewMatrix"
0(433) : error C1503: undefined variable "x_projectionMatrix"
*/
