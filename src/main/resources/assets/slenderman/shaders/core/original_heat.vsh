#version 150

// the vertices already come in the space of the camera (the pose is applied in Java); the normal travels in the colour
in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Params;    // x = strength, y = transparency, z = time (seconds), w = test mode
uniform vec2 Tiling;

out vec2 noiseUv;
out float amount;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vec3 N = normalize(mat3(ModelViewMat) * (Color.rgb * 2.0 - 1.0));
    vec3 V = normalize(-viewPos.xyz);
    float ndotv = dot(N, V);

    float depth = max(0.0, -viewPos.z);
    amount = Params.x * ndotv * ndotv / (1.0 + depth);
    noiseUv = UV0 * Tiling;
}
