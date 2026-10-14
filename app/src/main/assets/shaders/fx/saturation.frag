#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_saturation;  // -1..1

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    fragColor = premul(mix(vec3(luma(c)), c, 1.0 + u_saturation), p.a);
}
