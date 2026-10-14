#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_dotSize;  // px
uniform float u_angle;
uniform float u_monochrome;

void main() {
    vec2 px = toPx(v_uv);
    float cell = max(u_dotSize, 2.0);
    vec2 q = rotate2(px, -u_angle);
    vec2 center = (floor(q / cell) + 0.5) * cell;
    vec4 s = samplePx(rotate2(center, u_angle));
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(s);
    float l = luma(c);
    float d = length(q - center) / cell;
    float aa = 1.0 / cell;
    if (u_monochrome > 0.5) {
        float ink = 1.0 - smoothstep(sqrt(1.0 - l) * 0.7 - aa, sqrt(1.0 - l) * 0.7 + aa, d);
        fragColor = premul(vec3(1.0 - ink), p.a);
    } else {
        float dotMask = 1.0 - smoothstep(sqrt(l) * 0.7 - aa, sqrt(l) * 0.7 + aa, d);
        fragColor = premul(c * dotMask, p.a);
    }
}
