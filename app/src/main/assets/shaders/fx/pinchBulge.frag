#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;   // -1 pinch .. 1 bulge
uniform vec2 u_center;    // uv
uniform float u_radius;   // px
uniform float u_falloff;  // 0..1

void main() {
    vec2 px = toPx(v_uv);
    vec2 c = toPx(u_center);
    vec2 d = px - c;
    float r = length(d);
    if (r >= u_radius || r < 0.0001 || u_radius < 0.5) { fragColor = texture(u_texture, v_uv); return; }
    float t = r / u_radius;
    float e = exp2(u_amount * 1.5);  // > 1 samples nearer the centre (bulge), < 1 farther (pinch)
    float w = 1.0 - smoothstep(1.0 - max(u_falloff, 0.001), 1.0, t);
    float sr = mix(r, u_radius * pow(t, e), w);
    fragColor = samplePx(c + d / r * sr);
}
