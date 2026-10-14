#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_brightness;  // -1..1
uniform float u_contrast;    // -1..1

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p) + u_brightness;
    c = (c - 0.5) * (1.0 + u_contrast) + 0.5;
    fragColor = premul(c, p.a);
}
