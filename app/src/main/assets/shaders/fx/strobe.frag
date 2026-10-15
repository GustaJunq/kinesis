#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_frequency;  // Hz
uniform float u_duty;
uniform vec4 u_color;

void main() {
    vec4 p = texture(u_texture, v_uv);
    bool on = u_frequency > 0.0 && fract(u_time * u_frequency) < u_duty;
    if (!on) { fragColor = p; return; }
    if (u_mode == 1) { fragColor = vec4(0.0); return; }
    fragColor = premul(mix(unpremul(p), u_color.rgb, u_color.a), p.a);
}
