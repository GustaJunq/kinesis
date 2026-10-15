#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform vec2 u_position;   // uv
uniform float u_intensity;
uniform vec4 u_color;
uniform int u_rings;

void main() {
    vec4 p = texture(u_texture, v_uv);
    float scale = min(u_resolution.x, u_resolution.y);
    vec2 px = toPx(v_uv);
    vec2 src = toPx(u_position);
    vec2 center = u_resolution * 0.5;
    vec2 d = (px - src) / scale;
    float r = length(d);
    // Core, halo and horizontal streak.
    float light = exp(-r * r * 400.0) * 1.5 + exp(-r * 9.0) * 0.35;
    light += exp(-abs(d.y) * 260.0) * exp(-abs(d.x) * 4.0) * 0.5;
    vec3 col = u_color.rgb * light;
    // Ghost rings along the axis through the centre of the frame.
    vec2 axis = center - src;
    for (int i = 0; i < 12; i++) {
        if (i >= u_rings) break;
        float k = 0.35 + float(i) * 0.37;
        vec2 gpos = src + axis * k * 2.0;
        float size = (0.03 + 0.05 * fract(float(i) * 0.618)) * (1.0 + float(i) * 0.15);
        float dist = length(px - gpos) / scale;
        float ring = smoothstep(size, size * 0.6, dist) * (0.25 + 0.75 * smoothstep(size * 0.3, size, dist));
        vec3 tintCol = mix(u_color.rgb, vec3(0.4, 0.7, 1.0), fract(float(i) * 0.43));
        col += tintCol * ring * 0.18;
    }
    col *= u_intensity * u_color.a;
    float a = clamp(max(p.a, luma(col)), 0.0, 1.0);
    fragColor = vec4(min(p.rgb + col, vec3(a)), a);
}
