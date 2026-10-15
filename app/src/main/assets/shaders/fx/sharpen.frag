#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform sampler2D u_blurred;
uniform float u_amount;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec4 b = texture(u_blurred, v_uv);
    vec4 s = p + (p - b) * u_amount;
    float a = clamp(s.a, 0.0, 1.0);
    fragColor = vec4(clamp(s.rgb, vec3(0.0), vec3(a)), a);
}
