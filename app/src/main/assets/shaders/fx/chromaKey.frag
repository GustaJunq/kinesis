#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_color;
uniform float u_tolerance;
uniform float u_softness;
uniform float u_spill;
uniform float u_choke;  // px; > 0 shrinks the matte

vec2 chroma(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }

float keyAlpha(vec4 s) {
    float d = distance(chroma(unpremul(s)), chroma(u_color.rgb));
    return s.a * smoothstep(u_tolerance * 0.5, u_tolerance * 0.5 + u_softness * 0.5 + 0.0001, d);
}

void main() {
    vec4 p = texture(u_texture, v_uv);
    float a = keyAlpha(p);
    if (abs(u_choke) >= 0.5) {
        vec2 px = toPx(v_uv);
        for (int i = 0; i < 8; i++) {
            float ang = float(i) * PI * 0.25;
            float k = keyAlpha(samplePx(px + vec2(cos(ang), sin(ang)) * abs(u_choke)));
            a = u_choke > 0.0 ? min(a, k) : max(a, k);
        }
    }
    vec3 c = unpremul(p);
    // Spill: pull the key colour out of what remains.
    vec3 kd = u_color.rgb - luma(u_color.rgb);
    float kl = max(dot(kd, kd), 0.0001);
    c -= kd * max(0.0, dot(c - luma(c), kd) / kl) * u_spill;
    fragColor = premul(c, a);
}
