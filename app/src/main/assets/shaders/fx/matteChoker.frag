#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_choke;     // px; > 0 shrinks, < 0 grows
uniform float u_softness;  // px

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 px = toPx(v_uv);
    float a = p.a;
    float r = abs(u_choke) + u_softness;
    if (r >= 0.5) {
        int n = clamp(u_samples, 8, 32);
        float sum = 0.0;
        float lim = u_choke >= 0.0 ? 1.0 : 0.0;
        for (int i = 0; i < 32; i++) {
            if (i >= n) break;
            float ang = 2.0 * PI * float(i) / float(n);
            vec2 dir = vec2(cos(ang), sin(ang));
            float e = samplePx(px + dir * abs(u_choke)).a;
            lim = u_choke >= 0.0 ? min(lim, e) : max(lim, e);
            sum += samplePx(px + dir * r).a;
        }
        a = u_choke >= 0.0 ? min(a, lim) : max(a, lim);
        if (u_softness > 0.0) a = mix(a, sum / float(n), 0.5 * clamp(u_softness / max(r, 1.0), 0.0, 1.0));
    }
    fragColor = premul(unpremul(p), a);
}
