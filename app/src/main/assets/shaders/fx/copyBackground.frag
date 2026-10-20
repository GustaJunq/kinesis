#version 300 es
precision highp float;
precision highp int;
//#include fx/common.glsl
uniform sampler2D u_backdrop;  // composite of the layers below (premultiplied), same framing as the layer
uniform float u_amount;        // 0 = the layer as it is, 1 = the background

void main() {
    vec4 layer = texture(u_texture, v_uv);
    // The background seen through the layer's coverage: premultiplied colour times the layer alpha keeps the result valid
    // when the background itself is not opaque.
    vec4 bg = texture(u_backdrop, v_uv) * layer.a;
    fragColor = mix(layer, bg, u_amount);
}
