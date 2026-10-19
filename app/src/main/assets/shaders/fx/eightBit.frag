#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_pixelSize;   // px
uniform int u_colorLevels;   // per channel
uniform int u_paletteMode;   // 0 free, 1 greyscale, 2 console palette

// 16-colour console palette (PICO-8 style), RGB 0..1.
const vec3 PALETTE[16] = vec3[16](
    vec3(0.000, 0.000, 0.000), vec3(0.114, 0.169, 0.325), vec3(0.494, 0.145, 0.325), vec3(0.000, 0.529, 0.318),
    vec3(0.671, 0.322, 0.212), vec3(0.373, 0.341, 0.310), vec3(0.761, 0.765, 0.780), vec3(1.000, 0.945, 0.910),
    vec3(1.000, 0.000, 0.302), vec3(1.000, 0.639, 0.000), vec3(1.000, 0.925, 0.153), vec3(0.000, 0.894, 0.212),
    vec3(0.161, 0.678, 1.000), vec3(0.514, 0.463, 0.612), vec3(1.000, 0.467, 0.659), vec3(1.000, 0.800, 0.667)
);

void main() {
    // One sample per cell (its centre): the picture becomes a grid of flat pixels.
    vec2 cell = vec2(max(u_pixelSize, 1.0));
    vec2 centre = (floor(toPx(v_uv) / cell) + 0.5) * cell;
    vec4 s = samplePx(centre);
    vec3 c = unpremul(s);
    float steps = float(max(u_colorLevels, 2) - 1);
    if (u_paletteMode == 1) {
        c = vec3(floor(luma(c) * steps + 0.5) / steps);
    } else if (u_paletteMode == 2) {
        float best = 1e9;
        vec3 pick = PALETTE[0];
        for (int i = 0; i < 16; i++) {
            vec3 d = c - PALETTE[i];
            float dist = dot(d, d);
            if (dist < best) { best = dist; pick = PALETTE[i]; }
        }
        c = pick;
    } else {
        c = floor(c * steps + 0.5) / steps;
    }
    fragColor = premul(c, s.a);   // the layer's alpha is kept as it is
}
