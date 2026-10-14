#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_color;
uniform float u_amount;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    vec3 kd = u_color.rgb - luma(u_color.rgb);
    float kl = max(dot(kd, kd), 0.0001);
    c -= kd * max(0.0, dot(c - luma(c), kd) / kl) * u_amount;
    fragColor = premul(c, p.a);
}
