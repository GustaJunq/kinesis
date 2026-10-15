#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform sampler2D u_blur1;  // small radius
uniform sampler2D u_blur2;
uniform sampler2D u_blur3;  // full radius
uniform vec3 u_weights;     // normalized by the falloff
uniform float u_intensity;
uniform vec4 u_color;

void main() {
    vec4 src = texture(u_texture, v_uv);
    vec4 g = texture(u_blur1, v_uv) * u_weights.x + texture(u_blur2, v_uv) * u_weights.y + texture(u_blur3, v_uv) * u_weights.z;
    vec3 light = g.rgb * u_color.rgb * u_color.a * u_intensity;
    float a = clamp(max(src.a, max(light.r, max(light.g, light.b))), 0.0, 1.0);
    fragColor = vec4(min(src.rgb + light, vec3(a)), a);
}
