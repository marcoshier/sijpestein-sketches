#version 450 core
#define OR_IN_OUT
#define OR_GL
in vec2 v_texCoord0;
uniform sampler2D tex0;
out vec4 o_color;

vec4 ref = vec4(1.0);

float f(float t) {
    if (t > 0.008856) {
        return pow(t, 1.0 / 3.0);
    } else {
        return (903.3 * t + 16.0) / 116.0;
    }
}

vec4 toXYZa(vec4 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    float fx = lab.y / 500.0 + fy;
    float fz = fy - lab.z / 200.0;
    
    float x = 0.0;
    float y = 0.0;
    float z = 0.0;
    
    if (fx * fx * fx > 0.008856) {
        x = fx * fx * fx;
    } else {
        x = (116 * fx - 16) / 903.3;
    }

    if (lab.l > 903.3 * 0.008856) {
        y = pow((lab.x + 16) / 116.0, 3.0);
    } else {
        y = lab.x / 903.3;
    }

    if (fz * fz * fz > 0.008856) {
        z = fz * fz * fz;
    } else {
        z = (116.0 * fz - 16.0) / 903.3;
    }

    x *= ref.x;
    y *= ref.y;
    z *= ref.z;
    
    return vec4(x, y, z, lab.w)
}

vec4 toRGBa(vec4 xyz) {
    float r = 3.2406 * xyz.x - 1.5372 * xyz.y - 0.4986 * xyz.z;
    float g = -0.9689 * xyz.x + 1.8758 * xyz.y + 0.0415 * xyz.z;
    float b = 0.0557 * xyz.x - 0.2040 * xyz.y + 1.0570 * xyz.z;
    
    return vec4(r, g, b, xyz.w)
}

vec4 toLABa(vec4 xyz) {
    float x = xyz.x / ref.x;
    float y = xyz.y / ref.y;
    float z = xyz.z / ref.z;

    float l = 116 * f(y) - 16.0;
    float a = 500 * (f(x) - f(y));
    float b = 200 * (f(y) - f(z));

    return vec4(l, a, b, xyz.w);
}

void main() {
    vec4 lab = toLABa(texture(tex0, v_texCoord0));
    lab.x = 50.0; // normalize
    vec4 xyz = toXYZa(lab);
    vec4 rgb = toRGBa(xyz);
    o_color = rgb;
}
// -------------
// normalize-lightness-shader
// created 2024-01-29T11:57:07.927730800
/*
0(33) : error C1048: invalid character 'l' in swizzle "l"
0(50) : error C0000: syntax error, unexpected '}', expecting ',' or ';' at token "}"
0(18) : error C1110: function "toXYZa" has no return statement
0(58) : error C0000: syntax error, unexpected '}', expecting ',' or ';' at token "}"
0(52) : error C1110: function "toRGBa" has no return statement
*/
