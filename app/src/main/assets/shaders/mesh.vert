#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
// Wireframe meshes only: distance to the edges (1 on hidden edges); other meshes leave it at the default.
layout(location = 3) in vec3 a_bary;

uniform mat4 u_model;
uniform mat4 u_viewProj;
uniform mat3 u_normalMatrix;

out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_uv;
out vec3 v_bary;

void main() {
    vec4 world = u_model * vec4(a_position, 1.0);
    v_worldPos = world.xyz;
    v_normal = u_normalMatrix * a_normal;
    v_uv = a_uv;
    v_bary = a_bary;
    gl_Position = u_viewProj * world;
}
