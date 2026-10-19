#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_matrixSize;    // choice index: 0 = 2x2, 1 = 4x4, 2 = 8x8
uniform int u_levels;        // levels per channel
uniform float u_scale;       // px: size of one dither pixel
uniform float u_monochrome;  // 0/1
uniform vec4 u_colorA;       // darks (monochrome mode)
uniform vec4 u_colorB;       // lights (monochrome mode)

// Bayer matrix value (0 .. n*n - 1) of the cell (x, y) in a 2^bits matrix, from the interleaved bits of x ^ y and y.
int bayer(ivec2 p, int bits) {
    int n = 1 << bits;
    int x = p.x & (n - 1);
    int y = p.y & (n - 1);
    int v = 0;
    for (int i = 0; i < 3; i++) {
        if (i >= bits) break;
        int xb = (x >> i) & 1;
        int yb = (y >> i) & 1;
        v |= (((xb ^ yb) << 1) | yb) << (2 * (bits - 1 - i));
    }
    return v;
}

void main() {
    float cellPx = max(u_scale, 1.0);
    vec2 cell = floor(toPx(v_uv) / cellPx);
    vec4 s = samplePx((cell + 0.5) * cellPx);
    vec3 c = unpremul(s);
    int bits = u_matrixSize + 1;
    float n = float(1 << bits);
    float threshold = (float(bayer(ivec2(cell), bits)) + 0.5) / (n * n);
    float steps = float(max(u_levels, 2) - 1);
    if (u_monochrome > 0.5) {
        float q = clamp(floor(luma(c) * steps + threshold) / steps, 0.0, 1.0);
        vec4 col = mix(u_colorA, u_colorB, q);
        fragColor = premul(col.rgb, s.a * col.a);
    } else {
        fragColor = premul(clamp(floor(c * steps + threshold) / steps, 0.0, 1.0), s.a);
    }
}
