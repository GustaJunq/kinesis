#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_angle;
uniform float u_length;  // px

void main() {
    vec2 px = toPx(v_uv);
    vec2 dir = dirOf(u_angle) * u_length;
    int n = clamp(u_samples, 4, 64);
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= n) break;
        float t = (float(i) + 0.5) / float(n) - 0.5;
        sum += samplePx(px + dir * t);
    }
    fragColor = sum / float(n);
}
