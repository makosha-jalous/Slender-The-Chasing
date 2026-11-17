#version 150

// THE MASK: a plane that stands in the WORLD in front of Slenderman. It shows the picture of the screen (a copy taken just before)
// from a shifted place, so it bends everything behind it and him together with it.
// Made after the video of the original:
//  - HARD edges (no soft border): a window, inside it the picture is bent, outside it is untouched;
//  - few LONG waves: the trunks bend into big slow S-curves, and some vertical strands (the picture is pulled sideways in thin
//    vertical streaks) - not a fine ripple;
//  - the exposure is turned up to the maximum INSIDE the window (everything gets brighter: trees, stones, him) with a hard contrast:
//    the lights burn out, the darks stay black. It is NOT a light rectangle: nothing is painted over the picture, it is the same picture.
uniform sampler2D Sampler0;
uniform vec2 MaskScreen;
uniform vec4 Effect;   // x = exposure of the flash (0..2), y = not used, z = not used, w = shift of red and blue
uniform vec4 Wave;     // x = how far the picture is bent (share of the screen), y = time (s), z = long bends over the height, w = opacity
uniform vec4 Edge;     // x = not used (no soft border), y = stretch of THIS flash (0..1), z = seed of THIS flash, w = not used

in vec2 uv;
out vec4 fragColor;

const float EXPOSURE_GAIN = 1.0;     // brightness: 1 + exposure * gain (2.0 -> x3)
const float CONTRAST_GAIN = 0.45;    // hardness of the contrast
const float CONTRAST_PIVOT = 0.25;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    float t = Wave.y;
    float seed = Edge.z;
    float st = Edge.y;

    // the long S-bends (a few over the height), slowly changing
    float bend  = vnoise(vec2(uv.y * Wave.z + t * 0.6 + seed * 5.0, uv.x * 0.7 + seed * 2.0)) - 0.5;
    float bend2 = vnoise(vec2(uv.y * Wave.z * 0.55 - t * 0.4 + seed * 3.0, 3.0 + uv.x * 0.5)) - 0.5;
    // the vertical strands: changes quickly from left to right, slowly from top to bottom
    float strand = vnoise(vec2(uv.x * (9.0 + 8.0 * st) + seed * 7.0, uv.y * 0.9 - t * 0.5)) - 0.5;
    float strandAmp = 0.55 + 0.90 * vnoise(vec2(uv.y * 1.1 + seed * 4.0, t * 0.5));
    float sx = bend * 1.1 + bend2 * 0.8 + strand * strandAmp;
    // a little up / down drag: the tops of the trunks are pulled
    float sy = (vnoise(vec2(uv.x * 5.0 + seed * 3.0, uv.y * 1.2 + t * 0.5)) - 0.5) * (0.4 + 1.2 * st);
    vec2 off = vec2(sx, sy * 0.6) * Wave.x;

    vec2 sc = gl_FragCoord.xy / MaskScreen + off;
    vec3 c;
    c.r = texture(Sampler0, sc + vec2(Effect.w, 0.0)).r;
    c.g = texture(Sampler0, sc).g;
    c.b = texture(Sampler0, sc - vec2(Effect.w, 0.0)).b;

    // the exposure turned up + the hard contrast: lights burn out, darks go black
    float ex = Effect.x;
    c *= 1.0 + ex * EXPOSURE_GAIN;
    c = (c - CONTRAST_PIVOT) * (1.0 + ex * CONTRAST_GAIN) + CONTRAST_PIVOT;
    float peak = max(c.r, max(c.g, c.b));
    c = mix(c, vec3(peak), clamp(peak - 1.0, 0.0, 1.0) * 0.5);        // what is burnt out turns white

    float a = clamp(Wave.w, 0.0, 1.0);                                  // hard edges: the same opacity everywhere inside
    fragColor = vec4(clamp(c, 0.0, 1.0), a);
}
