#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_color;
uniform float u_amount;

void main() {
    vec4 p = texture(u_texture, v_uv);
    fragColor = premul(mix(unpremul(p), u_color.rgb, u_amount), p.a * mix(1.0, u_color.a, u_amount));
}
