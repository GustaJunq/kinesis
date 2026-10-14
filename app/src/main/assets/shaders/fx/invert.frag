#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    fragColor = premul(mix(c, 1.0 - c, u_amount), p.a);
}
