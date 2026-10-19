#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_cellSize;        // px
uniform int u_charset;           // unused here: the app picks the atlas (see Compositor.pixelEncoder)
uniform float u_useSourceColor;  // 0/1
uniform vec4 u_fgColor;
uniform vec4 u_bgColor;
uniform float u_invert;          // 0/1
uniform sampler2D u_atlas;       // one square per level, light to dark, white glyph on black
uniform int u_atlasLevels;

void main() {
    vec2 px = toPx(v_uv);
    vec2 cell = vec2(max(u_cellSize, 2.0));
    vec2 base = floor(px / cell) * cell;
    // Four taps inside the cell give its average colour and luminance.
    vec4 s = 0.25 * (samplePx(base + cell * vec2(0.25, 0.25)) + samplePx(base + cell * vec2(0.75, 0.25))
                   + samplePx(base + cell * vec2(0.25, 0.75)) + samplePx(base + cell * vec2(0.75, 0.75)));
    vec3 c = unpremul(s);
    float l = luma(c);
    if (u_invert > 0.5) l = 1.0 - l;
    int n = max(u_atlasLevels, 1);
    int idx = clamp(int(floor(l * float(n))), 0, n - 1);
    // Position inside the cell; the strip's rows run top to bottom, v_uv runs bottom to top.
    vec2 f = fract(px / cell);
    vec2 auv = vec2((float(idx) + clamp(f.x, 0.02, 0.98)) / float(n), clamp(1.0 - f.y, 0.02, 0.98));
    float g = texture(u_atlas, auv).r;
    vec4 fg = u_useSourceColor > 0.5 ? vec4(c, 1.0) : u_fgColor;
    vec3 col = mix(u_bgColor.rgb, fg.rgb, g);
    // The cell keeps the layer's alpha; the colours' own alpha only fades glyph and background.
    fragColor = premul(col, s.a * mix(u_bgColor.a, fg.a, g));
}
