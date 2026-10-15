#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_radius;      // px
uniform int u_blades;        // 0 = round aperture
uniform float u_rotation;    // degrees
uniform float u_brightness;  // highlight boost

// Distance from the centre to the edge of a regular polygon (unit circumradius) at angle `theta`.
float aperture(float theta) {
    if (u_blades < 3) return 1.0;
    float n = float(u_blades);
    float seg = 2.0 * PI / n;
    float t = mod(theta - radians(u_rotation), seg) - seg * 0.5;
    return cos(PI / n) / cos(t);
}

void main() {
    if (u_radius < 0.5) { fragColor = texture(u_texture, v_uv); return; }
    vec2 px = toPx(v_uv);
    int n = clamp(u_samples * 2, 8, 128);
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 128; i++) {
        if (i >= n) break;
        float fi = float(i) + 0.5;
        float r = sqrt(fi / float(n));
        float theta = fi * 2.39996323;  // golden angle
        vec2 o = vec2(cos(theta), sin(theta)) * r * aperture(theta) * u_radius;
        vec4 s = samplePx(px + o);
        float w = 1.0 + u_brightness * 6.0 * pow(luma(unpremul(s)), 4.0) * s.a;
        sum += s * w;
        wsum += w;
    }
    fragColor = sum / wsum;
}
