#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_strength;
uniform float u_invert;

float lum(vec2 px) { vec4 s = samplePx(px); return luma(s.rgb) + (1.0 - s.a) * 0.0; }

void main() {
    vec2 px = toPx(v_uv);
    float tl = lum(px + vec2(-1, 1)), t = lum(px + vec2(0, 1)), tr = lum(px + vec2(1, 1));
    float l = lum(px + vec2(-1, 0)), r = lum(px + vec2(1, 0));
    float bl = lum(px + vec2(-1, -1)), b = lum(px + vec2(0, -1)), br = lum(px + vec2(1, -1));
    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);
    float e = clamp(length(vec2(gx, gy)) * u_strength, 0.0, 1.0);
    if (u_invert > 0.5) e = 1.0 - e;
    vec4 p = texture(u_texture, v_uv);
    fragColor = premul(vec3(e), p.a);
}
