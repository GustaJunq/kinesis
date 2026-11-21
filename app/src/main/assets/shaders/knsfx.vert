#version 300 es
// Vertex shader of custom (.knsfx) effects: same quad as fullscreen.vert, with the contract's vTexCoord.
layout(location = 0) in vec2 a_pos;
out vec2 vTexCoord;

void main() {
    vTexCoord = vec2(a_pos.x, 1.0 - a_pos.y);
    gl_Position = vec4(a_pos.x * 2.0 - 1.0, 1.0 - a_pos.y * 2.0, 0.0, 1.0);
}
