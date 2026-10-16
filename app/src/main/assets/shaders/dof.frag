#version 300 es
precision highp float;
// Depth of field on a rendered 3D layer (premultiplied colour), gathering a golden-angle disc whose radius is the
// circle of confusion of each pixel.
in vec2 v_uv;
uniform sampler2D u_texture;
uniform sampler2D u_depth;      // lineardepth.frag output; (1, 0) where nothing was drawn
uniform vec2 u_texel;
uniform float u_far;
uniform float u_focus;
uniform float u_range;
uniform float u_maxBlur;        // output pixels
uniform int u_samples;
out vec4 fragColor;

float distanceAt(vec2 uv) {
    vec2 e = texture(u_depth, uv).rg;
    return (e.r * 255.0 + e.g) / 255.0 * u_far;
}

float cocAt(vec2 uv) {
    float d = distanceAt(uv);
    float off = max(abs(d - u_focus) - u_range * 0.5, 0.0);
    return clamp(off / max(u_focus * 0.5, 1.0), 0.0, 1.0) * u_maxBlur;
}

void main() {
    vec4 center = texture(u_texture, v_uv);
    float coc = cocAt(v_uv);
    if (coc < 0.5) {
        fragColor = center;
        return;
    }
    vec4 sum = center;
    float total = 1.0;
    float n = float(u_samples);
    for (int i = 0; i < 64; i++) {
        if (i >= u_samples) break;
        float fi = float(i) + 0.5;
        float r = sqrt(fi / n) * coc;
        float a = fi * 2.39996323;
        vec2 uv = v_uv + vec2(cos(a), sin(a)) * r * u_texel;
        // A sample only spreads here if its own blur reaches this far (keeps sharp things from smearing).
        float w = clamp(cocAt(uv) - r + 1.0, 0.0, 1.0);
        sum += texture(u_texture, uv) * w;
        total += w;
    }
    fragColor = sum / total;
}
