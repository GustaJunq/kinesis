#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_red;    // 0 red, 1 green, 2 blue, 3 alpha, 4 luma, 5 zero, 6 one
uniform int u_green;
uniform int u_blue;
uniform int u_alpha;

float pick(int k, vec3 c, float a) {
    if (k == 0) return c.r;
    if (k == 1) return c.g;
    if (k == 2) return c.b;
    if (k == 3) return a;
    if (k == 4) return luma(c);
    if (k == 5) return 0.0;
    return 1.0;
}

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    vec3 o = vec3(pick(u_red, c, p.a), pick(u_green, c, p.a), pick(u_blue, c, p.a));
    fragColor = premul(o, clamp(pick(u_alpha, c, p.a), 0.0, 1.0));
}
