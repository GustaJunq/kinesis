#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_amount;
uniform float u_softness;
uniform float u_aspect;  // width / height
out vec4 fragColor;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec2 d = (v_uv - 0.5) * vec2(u_aspect, 1.0);
    float r = length(d) / length(vec2(u_aspect, 1.0) * 0.5);
    float v = smoothstep(1.0 - u_softness, 1.0 + 0.0001, r);
    fragColor = vec4(p.rgb * (1.0 - v * u_amount), p.a);
}
