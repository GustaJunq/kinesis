#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform vec4 u_color;   // straight colour
uniform float u_amount;
out vec4 fragColor;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 straight = p.a > 0.0001 ? p.rgb / p.a : vec3(0.0);
    float l = dot(straight, vec3(0.2126, 0.7152, 0.0722));
    vec3 tinted = u_color.rgb * mix(1.0, l * 1.4, 0.35);
    fragColor = vec4(mix(straight, tinted, u_amount) * p.a, p.a);
}
