#version 150

// the mask of Distortion: a flat rectangle that stands in front of Slenderman, drawn on the screen (after the world, before the interface)
in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 localUv;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    localUv = UV0;      // place on the rectangle: 0..1 both ways
}
