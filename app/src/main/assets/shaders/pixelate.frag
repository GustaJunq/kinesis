#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec2 u_cell;  // cell size in uv units
out vec4 fragColor;

void main() {
    vec2 uv = (floor(v_uv / u_cell) + 0.5) * u_cell;
    fragColor = texture(u_texture, uv);
}
