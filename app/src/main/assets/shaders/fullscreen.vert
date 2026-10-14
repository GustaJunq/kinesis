#version 300 es
// Full-target pass for effects: a_pos is a unit quad (0..1).
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;

void main() {
    v_uv = vec2(a_pos.x, 1.0 - a_pos.y);
    gl_Position = vec4(a_pos.x * 2.0 - 1.0, 1.0 - a_pos.y * 2.0, 0.0, 1.0);
}
