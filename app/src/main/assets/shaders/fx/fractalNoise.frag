#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_scale;   // px
uniform int u_complexity;
uniform float u_evolution;
uniform float u_contrast;
uniform float u_brightness;
uniform vec4 u_colorA;
uniform vec4 u_colorB;
uniform float u_seed;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 evo = vec2(cos(radians(u_evolution)), sin(radians(u_evolution))) * 1.7;
    float n = fbm(toPx(v_uv) / max(u_scale, 1.0) + evo, u_complexity, u_seed);
    n = clamp((n - 0.5) * u_contrast + 0.5 + u_brightness, 0.0, 1.0);
    fragColor = combine(p, mix(u_colorA, u_colorB, n));
}
