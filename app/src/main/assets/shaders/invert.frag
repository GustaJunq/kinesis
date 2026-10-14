#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_amount;
out vec4 fragColor;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 straight = p.a > 0.0001 ? p.rgb / p.a : vec3(0.0);
    fragColor = vec4(mix(straight, 1.0 - straight, u_amount) * p.a, p.a);
}
