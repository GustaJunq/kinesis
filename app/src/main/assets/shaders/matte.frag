#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_texture;  // layer (premultiplied)
uniform sampler2D u_matte;    // matte source (premultiplied)
uniform int u_mode;           // 0 alpha, 1 alpha inverted, 2 luma, 3 luma inverted
out vec4 fragColor;

void main() {
    vec4 c = texture(u_texture, v_uv);
    vec4 m = texture(u_matte, v_uv);
    float k;
    if (u_mode == 0) k = m.a;
    else if (u_mode == 1) k = 1.0 - m.a;
    else {
        float luma = dot(m.rgb, vec3(0.2126, 0.7152, 0.0722)); // premultiplied: dark where transparent
        k = (u_mode == 2) ? luma : 1.0 - luma;
    }
    fragColor = c * clamp(k, 0.0, 1.0);
}
