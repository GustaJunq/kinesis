#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_brightness;  // -1..1
uniform float u_contrast;    // -1..1
uniform float u_saturation;  // -1..1
uniform float u_hue;         // degrees
out vec4 fragColor;

vec3 hueRotate(vec3 c, float degrees) {
    float a = radians(degrees);
    float cs = cos(a);
    float sn = sin(a);
    mat3 m = mat3(
        0.299 + 0.701 * cs + 0.168 * sn, 0.299 - 0.299 * cs - 0.328 * sn, 0.299 - 0.300 * cs + 1.250 * sn,
        0.587 - 0.587 * cs + 0.330 * sn, 0.587 + 0.413 * cs + 0.035 * sn, 0.587 - 0.588 * cs - 1.050 * sn,
        0.114 - 0.114 * cs - 0.497 * sn, 0.114 - 0.114 * cs + 0.292 * sn, 0.114 + 0.886 * cs - 0.203 * sn);
    return m * c;
}

void main() {
    vec4 p = texture(u_texture, v_uv);
    if (p.a <= 0.0001) { fragColor = vec4(0.0); return; }
    vec3 c = p.rgb / p.a;
    c = hueRotate(c, u_hue);
    c += u_brightness;
    c = (c - 0.5) * (1.0 + u_contrast) + 0.5;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, 1.0 + u_saturation);
    fragColor = vec4(clamp(c, 0.0, 1.0) * p.a, p.a);
}
