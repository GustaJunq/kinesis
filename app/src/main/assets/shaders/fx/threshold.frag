#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_threshold;

// Keeps the parts brighter than the threshold (deep glow source).
void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float k = smoothstep(u_threshold, min(u_threshold + 0.2, 1.0001), luma(c));
    fragColor = p * k;
}
