#version 300 es
// Draws a rectangle given in layer-local pixels. a_pos is a unit quad (0..1, y down).
layout(location = 0) in vec2 a_pos;

uniform mat4 u_mvp;        // layer-local pixels -> clip space
uniform vec4 u_rect;       // x, y, width, height of the rectangle in layer-local pixels
uniform mat4 u_texMatrix;  // applied to the texture coordinate (video transform, rotation)
uniform float u_flipY;     // 1 for textures stored bottom-up (framebuffers, video)

out vec2 v_uv;
out vec2 v_local;

void main() {
    vec2 local = u_rect.xy + a_pos * u_rect.zw;
    v_local = local;
    vec2 uv = vec2(a_pos.x, mix(a_pos.y, 1.0 - a_pos.y, u_flipY));
    v_uv = (u_texMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = u_mvp * vec4(local, 0.0, 1.0);
}
