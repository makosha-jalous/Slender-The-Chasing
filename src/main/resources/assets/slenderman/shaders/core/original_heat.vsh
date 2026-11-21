#version 150

// Vertex part of the original "HeatDistortion" shader (Unity):
//   amount = strength * (N . V)^2 / (1 + depth)          depth = distance along the view axis
//   noise uv = uv * tiling
in vec3 Position;   // camera-relative position of the vertex on the ellipsoid
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Params;    // x = strength, y = transparency, z = time (seconds)
uniform vec2 Tiling;
uniform vec3 Center;    // centre of the ellipsoid (camera-relative)
uniform vec3 Radii;

out vec2 noiseUv;
out float amount;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    // normal of an ellipsoid at this point, then into view space
    vec3 n = (Position - Center) / (Radii * Radii);
    vec3 N = normalize(mat3(ModelViewMat) * n);
    vec3 V = normalize(-viewPos.xyz);
    float ndotv = dot(N, V);

    float depth = max(0.0, -viewPos.z);
    amount = Params.x * ndotv * ndotv / (1.0 + depth);
    noiseUv = UV0 * Tiling;
}
