#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_spacing;    // px
uniform float u_intensity;
uniform float u_curvature;

void main() {
    vec2 uv = v_uv;
    if (u_curvature > 0.0) {
        vec2 c = uv * 2.0 - 1.0;
        c *= 1.0 + dot(c, c) * u_curvature * 0.25;
        uv = c * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { fragColor = vec4(0.0); return; }
    }
    vec4 p = texture(u_texture, uv);
    float line = 0.5 + 0.5 * cos(2.0 * PI * uv.y * u_resolution.y / max(u_spacing, 2.0));
    fragColor = vec4(p.rgb * (1.0 - u_intensity * line), p.a);
}
