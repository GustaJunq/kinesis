#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform float u_hue;         // degrees
uniform float u_saturation;  // 0..1
uniform float u_lightness;   // -1..1
uniform float u_amount;      // 0..1

vec3 hsl2rgb(float h, float s, float l) {
    float c = (1.0 - abs(2.0 * l - 1.0)) * s;
    float hp = mod(h, 360.0) / 60.0;
    float x = c * (1.0 - abs(mod(hp, 2.0) - 1.0));
    vec3 rgb = hp < 1.0 ? vec3(c, x, 0.0) : hp < 2.0 ? vec3(x, c, 0.0) : hp < 3.0 ? vec3(0.0, c, x)
             : hp < 4.0 ? vec3(0.0, x, c) : hp < 5.0 ? vec3(x, 0.0, c) : vec3(c, 0.0, x);
    return rgb + (l - 0.5 * c);
}

// The picture's own luma decides the lightness, so lights and shadows stay; hue and saturation come from the parameters.
void main() {
    vec4 p = texture(u_texture, v_uv);
    vec3 c = unpremul(p);
    float l = clamp(luma(c) + u_lightness * 0.5, 0.0, 1.0);
    vec3 painted = hsl2rgb(u_hue, clamp(u_saturation, 0.0, 1.0), l);
    fragColor = premul(mix(c, painted, u_amount), p.a);
}
