#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

in vec2 v_uv;
in vec2 v_local;
in vec2 v_uvAlpha;
uniform float u_alphaPacked;
uniform samplerExternalOES u_texture;
uniform float u_opacity;
uniform float u_premultiplied;
out vec4 fragColor;

//#include mask_common.glsl

void main() {
    vec4 c = texture(u_texture, v_uv);
    if (u_alphaPacked > 0.5) {
        // Straight colour + alpha stored as grey: premultiply here.
        float a = texture(u_texture, v_uvAlpha).r;
        c = vec4(c.rgb * a, a);
    } else {
        c.a = 1.0;
    }
    float k = u_opacity * maskCoverage(v_local) * wipeCoverage(v_local);
    fragColor = c * k;
}
