#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_vibrance;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float k = u_vibrance * (1.0 - sat);
    fragColor = premul(mix(vec3(luma(c)), c, 1.0 + k), p.a);
}
