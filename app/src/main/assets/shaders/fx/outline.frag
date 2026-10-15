#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_color;
uniform float u_width;     // px
uniform float u_opacity;
uniform int u_position;    // 0 outside, 1 inside, 2 centre

float ringAlpha(vec2 px, float radius, bool takeMax) {
    int n = clamp(u_samples, 8, 48);
    float v = takeMax ? 0.0 : 1.0;
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        float a = 2.0 * PI * float(i) / float(n);
        for (int k = 1; k <= 3; k++) {
            float s = samplePx(px + vec2(cos(a), sin(a)) * radius * float(k) / 3.0).a;
            v = takeMax ? max(v, s) : min(v, s);
        }
    }
    return v;
}

void main() {
    vec4 p = texture(u_texture, v_uv);
    if (u_width < 0.5) { fragColor = p; return; }
    vec2 px = toPx(v_uv);
    float line;
    if (u_position == 0) line = clamp(ringAlpha(px, u_width, true) - p.a, 0.0, 1.0);
    else if (u_position == 1) line = clamp(p.a - ringAlpha(px, u_width, false), 0.0, 1.0);
    else line = clamp(ringAlpha(px, u_width * 0.5, true) - ringAlpha(px, u_width * 0.5, false), 0.0, 1.0);
    float a = line * u_opacity * u_color.a;
    vec4 o = vec4(u_color.rgb * a, a);
    fragColor = u_position == 0 ? p + o * (1.0 - p.a) : o + p * (1.0 - a);
}
