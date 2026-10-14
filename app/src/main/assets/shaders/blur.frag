#version 300 es
precision highp float;
// Separable Gaussian blur (one direction per pass) on premultiplied colour.
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec2 u_step;      // texel offset between taps, along the blur direction
uniform float u_sigma;    // in taps
out vec4 fragColor;

void main() {
    vec4 sum = texture(u_texture, v_uv);
    float total = 1.0;
    for (int i = 1; i <= 24; i++) {
        float x = float(i);
        float w = exp(-0.5 * x * x / (u_sigma * u_sigma));
        if (w < 0.002) break;
        sum += (texture(u_texture, v_uv + u_step * x) + texture(u_texture, v_uv - u_step * x)) * w;
        total += 2.0 * w;
    }
    fragColor = sum / total;
}
