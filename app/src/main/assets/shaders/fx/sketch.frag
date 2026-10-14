#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_strength;
uniform float u_colored;

float lum(vec2 px) { vec4 s = samplePx(px); return luma(unpremul(s)) * s.a + (1.0 - s.a); }

void main() {
    vec2 px = toPx(v_uv);
    float r = max(u_pxScale, 1.0);
    float gx = lum(px + vec2(r, 0)) - lum(px - vec2(r, 0));
    float gy = lum(px + vec2(0, r)) - lum(px - vec2(0, r));
    float e = clamp(length(vec2(gx, gy)) * 2.5 * u_strength, 0.0, 1.0);
    // Diagonal hatching in the darker areas.
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float hatch = step(0.5, fract((px.x + px.y) / (6.0 * r))) * (1.0 - smoothstep(0.2, 0.6, luma(c))) * 0.35;
    float pencil = 1.0 - clamp(e + hatch * u_strength, 0.0, 1.0);
    vec3 paper = u_colored > 0.5 ? mix(vec3(1.0), c, 0.6) : vec3(1.0);
    fragColor = premul(paper * pencil, p.a);
}
