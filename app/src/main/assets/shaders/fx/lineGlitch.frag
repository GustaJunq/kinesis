#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_lineHeight;  // px
uniform float u_offset;      // px
uniform float u_density;
uniform float u_rate;
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    float step = floor(u_time * u_rate);
    float row = floor(px.y / max(u_lineHeight, 1.0));
    if (hash3(row, step, u_seed) < u_density) px.x += (hash3(row, step, u_seed + 11.0) - 0.5) * 2.0 * u_offset;
    fragColor = samplePx(px);
}
