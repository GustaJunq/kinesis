#version 300 es
precision highp float;
// Composites a shadow (or glow) made from a blurred copy under/over the source.
in vec2 v_uv;
uniform sampler2D u_texture;   // source (premultiplied)
uniform sampler2D u_blurred;   // blurred source
uniform vec2 u_offset;         // uv offset of the shadow
uniform vec4 u_color;          // straight colour, alpha = opacity / intensity
uniform float u_glow;          // 0 = drop shadow (under), 1 = glow (added)
out vec4 fragColor;

void main() {
    vec4 src = texture(u_texture, v_uv);
    float a = texture(u_blurred, v_uv - u_offset).a;
    if (u_glow > 0.5) {
        float g = clamp(a * u_color.a, 0.0, 1.0);
        vec3 rgb = src.rgb + u_color.rgb * g * (1.0 - src.a * 0.5);
        fragColor = vec4(min(rgb, vec3(max(src.a, g))), max(src.a, g));
    } else {
        float s = a * u_color.a;
        fragColor = src + vec4(u_color.rgb * s, s) * (1.0 - src.a);
    }
}
