#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_spacing;  // px
uniform float u_width;    // px
uniform vec4 u_color;
uniform float u_angle;
uniform vec2 u_offset;    // uv

void main() {
    vec4 p = texture(u_texture, v_uv);
    float s = max(u_spacing, 2.0);
    vec2 q = rotate2(toPx(v_uv) - toPx(u_offset), -u_angle);
    vec2 m = abs(mod(q + s * 0.5, s) - s * 0.5);
    float on = 1.0 - smoothstep(u_width * 0.5 - 0.5, u_width * 0.5 + 0.5, min(m.x, m.y));
    fragColor = combine(p, vec4(u_color.rgb, u_color.a * on));
}
