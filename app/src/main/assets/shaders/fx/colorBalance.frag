#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec4 u_shadows;     // mid grey = no change
uniform vec4 u_midtones;
uniform vec4 u_highlights;
uniform float u_preserveLuminosity;

void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float l = luma(c);
    float ws = 1.0 - smoothstep(0.0, 0.5, l);
    float wh = smoothstep(0.5, 1.0, l);
    float wm = 1.0 - abs(l - 0.5) * 2.0;
    vec3 shift = (u_shadows.rgb - 0.5) * ws + (u_midtones.rgb - 0.5) * wm + (u_highlights.rgb - 0.5) * wh;
    vec3 o = c + shift;
    if (u_preserveLuminosity > 0.5) o += l - luma(o);
    fragColor = premul(o, p.a);
}
