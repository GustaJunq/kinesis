#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_exposure;  // EV
uniform float u_offset;
uniform float u_gamma;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p) * exp2(u_exposure) + u_offset;
    c = pow(max(c, 0.0), vec3(1.0 / u_gamma));
    fragColor = premul(c, p.a);
}
