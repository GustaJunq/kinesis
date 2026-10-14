#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;  // px
uniform float u_angle;
uniform float u_rate;
uniform float u_seed;

void main() {
    float angle = u_angle;
    float amount = u_amount;
    if (u_rate > 0.0) {
        float step = floor(u_time * u_rate);
        angle += hash3(step, 1.0, u_seed) * 360.0;
        amount *= 0.4 + 1.2 * hash3(step, 2.0, u_seed);
    }
    vec2 o = toUv(dirOf(angle) * amount);
    vec4 r = texture(u_texture, v_uv + o);
    vec4 g = texture(u_texture, v_uv);
    vec4 b = texture(u_texture, v_uv - o);
    fragColor = vec4(r.r, g.g, b.b, max(g.a, max(r.a, b.a)));
}
