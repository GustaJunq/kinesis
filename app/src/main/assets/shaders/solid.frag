#version 300 es
precision highp float;
// Flat colour (premultiplied), used for backgrounds and editor overlays.
uniform vec4 u_color;
out vec4 fragColor;

void main() {
    fragColor = vec4(u_color.rgb * u_color.a, u_color.a);
}
