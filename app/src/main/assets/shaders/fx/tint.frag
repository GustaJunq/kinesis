#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_color;
uniform float u_amount;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    vec3 tinted = u_color.rgb * mix(1.0, luma(c) * 1.4, 0.35);
    fragColor = premul(mix(c, tinted, u_amount), p.a);
}
