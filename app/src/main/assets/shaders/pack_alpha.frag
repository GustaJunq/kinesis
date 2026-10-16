#version 300 es
precision highp float;
// Packed alpha for video: straight colour on the left half, alpha as grey on the right half.
in vec2 v_uv;
uniform sampler2D u_texture; // premultiplied
out vec4 fragColor;

void main() {
    bool alphaHalf = v_uv.x >= 0.5;
    vec2 uv = vec2(alphaHalf ? (v_uv.x - 0.5) * 2.0 : v_uv.x * 2.0, v_uv.y);
    vec4 c = texture(u_texture, uv);
    if (alphaHalf) {
        fragColor = vec4(vec3(c.a), 1.0);
    } else {
        fragColor = vec4(c.a > 0.0001 ? clamp(c.rgb / c.a, 0.0, 1.0) : vec3(0.0), 1.0);
    }
}
