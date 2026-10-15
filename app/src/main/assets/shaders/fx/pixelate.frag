#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_size;  // px

void main() {
    vec2 cell = vec2(max(u_size, 1.0));
    vec2 px = (floor(toPx(v_uv) / cell) + 0.5) * cell;
    fragColor = samplePx(px);
}
