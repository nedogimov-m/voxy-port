#version 430

uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;

in vec2 UV;
void main() {
    gl_FragDepth = texture(depthTex, UV*scaleFactor).r;
}