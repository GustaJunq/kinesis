#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amount;
uniform float u_softness;

void main() {
    vec4 p = texture(u_texture, v_uv);
    float aspect = u_resolution.x / u_resolution.y;
    vec2 d = (v_uv - 0.5) * vec2(aspect, 1.0);
    float r = length(d) / length(vec2(aspect, 1.0) * 0.5);
    float v = smoothstep(1.0 - u_softness, 1.0 + 0.0001, r);
    fragColor = vec4(p.rgb * (1.0 - v * u_amount), p.a);
}
