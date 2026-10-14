#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_tracking;
uniform float u_jitter;
uniform float u_colorBleed;  // px
uniform float u_noise;
uniform float u_scanlines;
uniform float u_seed;

void main() {
    vec2 px = toPx(v_uv);
    float frame = floor(u_time * 30.0);
    float y = v_uv.y;
    // A tracking band rolling down the frame.
    float bandPos = 1.0 - fract(u_time * 0.15 + hash3(u_seed, 1.0, 2.0));
    float band = smoothstep(0.08, 0.0, abs(y - bandPos)) * u_tracking;
    float line = floor(px.y / max(2.0 * u_pxScale, 1.0));
    float jit = (hash3(line, frame, u_seed) - 0.5) * u_jitter * 12.0 * u_pxScale;
    px.x += jit + band * (hash3(line, frame, u_seed + 5.0) - 0.5) * 80.0 * u_pxScale;
    float bleed = u_colorBleed;
    vec4 g = samplePx(px);
    float r = samplePx(px - vec2(bleed, 0.0)).r;
    float b = samplePx(px + vec2(bleed, 0.0)).b;
    vec3 c = unpremul(vec4(r, g.g, b, g.a));
    float n = hash3(floor(px.x / 2.0), floor(px.y / 2.0), u_seed + frame * 13.0);
    c = mix(c, vec3(n), u_noise * 0.5 + band * 0.6);
    c *= 1.0 - u_scanlines * (0.5 + 0.5 * cos(PI * px.y / max(u_pxScale * 1.5, 1.0)));
    fragColor = premul(c, g.a);
}
