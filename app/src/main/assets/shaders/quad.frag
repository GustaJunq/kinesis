#version 300 es
precision highp float;

in vec2 v_uv;
in vec2 v_local;
uniform sampler2D u_texture;
uniform float u_opacity;
uniform float u_premultiplied; // 1 if the texture already holds premultiplied alpha
out vec4 fragColor;

//#include mask_common.glsl

void main() {
    vec4 c = texture(u_texture, v_uv);
    if (u_premultiplied < 0.5) c.rgb *= c.a;
    float k = u_opacity * maskCoverage(v_local) * wipeCoverage(v_local);
    fragColor = c * k;
}
