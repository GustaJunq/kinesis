#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_angle;
uniform float u_strength;
uniform float u_distance;  // px

void main() {
    vec2 px = toPx(v_uv);
    vec2 d = dirOf(u_angle) * max(u_distance, 0.5);
    vec4 p = texture(u_texture, v_uv);
    float a = luma(unpremul(samplePx(px - d))) * samplePx(px - d).a;
    float b = luma(unpremul(samplePx(px + d))) * samplePx(px + d).a;
    fragColor = premul(vec3(0.5 + (a - b) * u_strength), p.a);
}
