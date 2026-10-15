#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_invert;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float l = luma(c);
    if (u_invert > 0.5) l = 1.0 - l;
    fragColor = premul(c, p.a * l);
}
