#version 300 es
precision highp float;
// TimeSlice: adds strip u_index of u_strips of the picture u_texture, weighted by how much that strip counts at the
// pixel (the weights of all strips sum to 1, see TimeSlice.weight in the core). Drawn with additive blending.
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec2 u_size;      // output size in pixels
uniform vec2 u_dir;       // unit direction of the strips (x right, y down)
uniform int u_strips;
uniform int u_index;
uniform float u_smooth;
out vec4 fragColor;

void main() {
    vec2 p = vec2(v_uv.x - 0.5, 0.5 - v_uv.y) * u_size;       // pixels from the centre, y down
    float half_ = (abs(u_dir.x) * u_size.x + abs(u_dir.y) * u_size.y) * 0.5;
    float s = half_ > 1e-6 ? clamp(dot(p, u_dir) / half_ * 0.5 + 0.5, 0.0, 1.0) : 0.5;
    float n = float(u_strips);
    float at = clamp(s * n, 0.0, n - 1e-4);
    float i = floor(at);
    float pos = at - i;
    float h = max(clamp(u_smooth, 0.0, 1.0) * 0.5, 1e-4);
    float below = pos < h ? 0.5 - 0.5 * pos / h : 0.0;
    float above = (1.0 - pos) < h ? 0.5 - 0.5 * (1.0 - pos) / h : 0.0;
    float k = float(u_index);
    float w = 0.0;
    if (k == i) w = 1.0 - (i > 0.0 ? below : 0.0) - (i < n - 1.0 ? above : 0.0);
    else if (k == i - 1.0) w = i > 0.0 ? below : 0.0;
    else if (k == i + 1.0) w = i < n - 1.0 ? above : 0.0;
    fragColor = texture(u_texture, v_uv) * w;
}
