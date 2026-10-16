#version 300 es
precision mediump float;
// Depth only: the shadow map is the depth attachment.
out vec4 fragColor;

void main() {
    fragColor = vec4(1.0);
}
