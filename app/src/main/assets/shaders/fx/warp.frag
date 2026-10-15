#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amplitude;   // px (wave)
uniform float u_frequency;   // waves per 1000 px
uniform float u_direction;   // degrees: displacement direction
uniform float u_phase;       // degrees
uniform float u_amount;      // px (fractal)
uniform float u_scale;       // px
uniform int u_complexity;
uniform float u_evolution;   // degrees; 360 loops back
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    vec2 offset;
    if (u_mode == 0) {
        vec2 dir = dirOf(u_direction);
        vec2 across = vec2(-dir.y, dir.x);
        offset = dir * u_amplitude * sin(2.0 * PI * u_frequency * dot(px, across) / (1000.0 * u_pxScale) + radians(u_phase));
    } else {
        vec2 evo = vec2(cos(radians(u_evolution)), sin(radians(u_evolution))) * 1.7;
        vec2 q = px / max(u_scale, 1.0) + evo;
        offset = (vec2(fbm(q, u_complexity, u_seed), fbm(q + vec2(31.7, 11.3), u_complexity, u_seed + 101.0)) - 0.5) * 2.0 * u_amount;
    }
    fragColor = samplePx(px + offset);
}
