#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;  // spin: degrees, zoom: % of the distance to the centre
uniform vec2 u_center;   // uv

void main() {
    vec2 px = toPx(v_uv);
    vec2 c = toPx(u_center);
    int n = clamp(u_samples, 4, 64);
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= n) break;
        float t = (float(i) + 0.5) / float(n) - 0.5;
        vec2 q;
        if (u_mode == 0) q = c + rotate2(px - c, u_amount * t);
        else q = c + (px - c) * (1.0 - u_amount * 0.01 * (t + 0.5));
        sum += samplePx(q);
    }
    fragColor = sum / float(n);
}
