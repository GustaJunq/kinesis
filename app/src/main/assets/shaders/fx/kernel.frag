#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform int u_preset;        // 0 sharpen, 1 emboss, 2 edge detect, 3 outline, 4 custom
uniform float u_strength;    // mix with the original
uniform float u_weight0; uniform float u_weight1; uniform float u_weight2;
uniform float u_weight3; uniform float u_weight4; uniform float u_weight5;
uniform float u_weight6; uniform float u_weight7; uniform float u_weight8;

void main() {
    float k[9];
    float bias = 0.0;
    if (u_preset == 0) {
        k = float[9](0.0, -1.0, 0.0, -1.0, 5.0, -1.0, 0.0, -1.0, 0.0);
    } else if (u_preset == 1) {
        k = float[9](-2.0, -1.0, 0.0, -1.0, 1.0, 1.0, 0.0, 1.0, 2.0);
        bias = 0.5;
    } else if (u_preset == 2) {
        k = float[9](-1.0, -1.0, -1.0, -1.0, 8.0, -1.0, -1.0, -1.0, -1.0);
    } else if (u_preset == 3) {
        k = float[9](1.0, 1.0, 1.0, 1.0, -8.0, 1.0, 1.0, 1.0, 1.0);
        bias = 1.0;   // light paper, dark lines
    } else {
        k = float[9](u_weight0, u_weight1, u_weight2, u_weight3, u_weight4, u_weight5, u_weight6, u_weight7, u_weight8);
    }
    // One composition pixel between taps, so the look does not depend on the preview resolution.
    float step = max(u_pxScale, 0.5);
    vec2 px = toPx(v_uv);
    vec4 centre = samplePx(px);
    vec3 acc = vec3(0.0);
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            vec2 d = vec2(float(i - 1), float(1 - j)) * step;   // row 0 is the top row (y up)
            acc += k[j * 3 + i] * unpremul(samplePx(px + d));
        }
    }
    vec3 result = mix(unpremul(centre), acc + bias, clamp(u_strength, 0.0, 1.0));
    fragColor = premul(result, centre.a);   // the layer's alpha is kept as it is
}
