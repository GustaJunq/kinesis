#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_stopsCount;
uniform float u_stopsOffset[8];
uniform vec4 u_stopsColor[8];  // straight colour
uniform float u_amount;

vec4 gradientAt(float x) {
    vec4 c = u_stopsColor[0];
    if (x <= u_stopsOffset[0]) return c;
    for (int i = 1; i < 8; i++) {
        if (i >= u_stopsCount) break;
        float o0 = u_stopsOffset[i - 1], o1 = u_stopsOffset[i];
        if (x <= o1) return mix(u_stopsColor[i - 1], u_stopsColor[i], o1 > o0 ? (x - o0) / (o1 - o0) : 1.0);
        c = u_stopsColor[i];
    }
    return c;
}

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    vec4 g = gradientAt(luma(c));
    fragColor = premul(mix(c, g.rgb, u_amount), p.a * mix(1.0, g.a, u_amount));
}
