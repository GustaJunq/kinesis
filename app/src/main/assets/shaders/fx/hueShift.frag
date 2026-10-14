#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_hue;  // degrees

void main() {
    vec4 p = texture(u_texture, v_uv);
    float a = radians(u_hue);
    float cs = cos(a), sn = sin(a);
    mat3 m = mat3(
        0.299 + 0.701 * cs + 0.168 * sn, 0.299 - 0.299 * cs - 0.328 * sn, 0.299 - 0.300 * cs + 1.250 * sn,
        0.587 - 0.587 * cs + 0.330 * sn, 0.587 + 0.413 * cs + 0.035 * sn, 0.587 - 0.588 * cs - 1.050 * sn,
        0.114 - 0.114 * cs - 0.497 * sn, 0.114 - 0.114 * cs + 0.292 * sn, 0.114 + 0.886 * cs - 0.203 * sn);
    fragColor = premul(m * unpremul(p), p.a);
}
