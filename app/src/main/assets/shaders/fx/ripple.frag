#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_amplitude;   // px
uniform float u_wavelength;  // px
uniform float u_phase;       // degrees
uniform float u_radius;      // px
uniform vec2 u_center;       // uv

void main() {
    vec2 px = toPx(v_uv);
    vec2 d = px - toPx(u_center);
    float r = length(d);
    if (r < 0.0001) { fragColor = texture(u_texture, v_uv); return; }
    float fade = 1.0 - smoothstep(0.0, max(u_radius, 1.0), r);
    float disp = u_amplitude * sin(2.0 * PI * r / max(u_wavelength, 1.0) - radians(u_phase)) * fade;
    fragColor = samplePx(px + d / r * disp);
}
