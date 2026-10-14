#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec2 u_offset;  // uv units
out vec4 fragColor;

void main() {
    vec4 r = texture(u_texture, v_uv + u_offset);
    vec4 g = texture(u_texture, v_uv);
    vec4 b = texture(u_texture, v_uv - u_offset);
    float a = max(g.a, max(r.a, b.a));
    fragColor = vec4(r.r, g.g, b.b, a);
}
