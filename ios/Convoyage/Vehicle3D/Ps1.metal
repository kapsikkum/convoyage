#include <metal_stdlib>
using namespace metal;
#include <SceneKit/scn_metal>

// PS1-style shading for the vehicles (port of android/.../render/Ps1Gl.kt, itself a port of design-tools/ps1.html):
//   - vertex snap to the low-resolution pixel grid
//   - affine texture mapping (uv * w and w are interpolated, the fragment stage divides)
//   - Gouraud lighting per vertex, with the finish's specular term and the pearl hue shift on paint
//   - 4x4 Bayer dither, a gamma lift, then 15-bit colour
//   - the three tiny textures (body, wheel face, ground) are procedural and sampled by whole texels (nearest),
//     so there are no texture objects to bind.
//
// Per-vertex colour: rgb = the linear tint, a = tex * 4 + flag
//   tex  0 none, 1 body, 2 wheel face, 3 ground
//   flag 0 plain, 1 paint (gets the finish), 2 unlit

struct Ps1NodeBuffer {
    float4x4 modelTransform;
    float4x4 inverseModelTransform;
    float4x4 modelViewTransform;
    float4x4 inverseModelViewTransform;
    float4x4 normalTransform;
    float4x4 modelViewProjectionTransform;
    float2x3 boundingBox;
    float2x3 worldBoundingBox;
};

struct Ps1Params {
    float4 res;     // x, y: render target size in pixels
    float4 look;    // spec, shine, metal, pearl
    float4 specCol; // rgb
    float4 wheel;   // spokes, lip, solid
    float4 light;   // xyz: light direction in view space
};

struct Ps1In {
    float3 position [[attribute(SCNVertexSemanticPosition)]];
    float3 normal   [[attribute(SCNVertexSemanticNormal)]];
    float4 color    [[attribute(SCNVertexSemanticColor)]];
    float2 uv       [[attribute(SCNVertexSemanticTexcoord0)]];
};

struct Ps1Out {
    float4 position [[position]];
    float3 uvw;
    float3 col;
    float3 spec;
    float  code [[flat]];
    float3 wheel [[flat]];
};

constant int kBayer[16] = { 0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5 };

float3 ps1_hue(float3 c, float a) {
    float3 k = float3(0.57735);
    return c * cos(a) + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cos(a));
}

vertex Ps1Out ps1_vertex(Ps1In in [[stage_in]],
                         constant Ps1NodeBuffer& scn_node [[buffer(1)]],
                         constant Ps1Params& params [[buffer(2)]]) {
    Ps1Out o;
    float4 vp = scn_node.modelViewTransform * float4(in.position, 1.0);
    float4 p = scn_node.modelViewProjectionTransform * float4(in.position, 1.0);
    float2 g = max(params.res.xy, float2(1.0)) * 0.5;
    if (p.w > 0.0001) {
        float2 ndc = p.xy / p.w;
        ndc = floor(ndc * g + 0.5) / g;           // vertex snap to the low-res grid
        p.xy = ndc * p.w;
    }
    o.position = p;
    o.uvw = float3(in.uv * p.w, p.w);             // affine mapping: undo perspective-correct interpolation

    int code = int(in.color.a + 0.5);
    int flag = code & 3;
    bool unlit = (flag == 2);
    bool paint = (flag == 1);

    float3 n = normalize((scn_node.modelViewTransform * float4(in.normal, 0.0)).xyz);
    float3 L = normalize(params.light.xyz);
    float d = max(dot(n, L), 0.0);
    float rim = pow(1.0 - max(n.z, 0.0), 2.0) * 0.25;
    float l = unlit ? 1.0 : (0.38 + 0.85 * d + rim);

    float3 tint = in.color.rgb;
    float specAmt = params.look.x;
    float shine = max(params.look.y, 1.0);
    float metal = params.look.z;
    float pearl = params.look.w;
    if (paint) {
        tint *= 1.0 - 0.28 * metal;
        tint = ps1_hue(tint, pearl * (n.x * 0.9 + n.y * 0.5 - 0.1));
    }
    float3 V = normalize(-vp.xyz);
    float s = pow(max(dot(n, normalize(L + V)), 0.0), shine);
    o.col = tint * l;
    o.spec = paint ? params.specCol.rgb * (specAmt * s) : float3(0.0);
    o.code = float(code);
    o.wheel = params.wheel.xyz;
    return o;
}

// MARK: - Procedural textures (nearest filtered: everything works on whole 32x32 texels)

float3 ps1_srgb_to_linear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
    return float3(c.x <= 0.04045 ? lo.x : hi.x, c.y <= 0.04045 ? lo.y : hi.y, c.z <= 0.04045 ? lo.z : hi.z);
}

float3 ps1_body(float2 uv) {
    int tx = int(floor(clamp(uv.x, 0.0, 0.9999) * 32.0));
    int y = 31 - int(floor(clamp(uv.y, 0.0, 0.9999) * 32.0));   // canvas row, 0 = top
    float v = (225.0 + float((tx * 7 + y * 13) % 5) * 6.0) / 255.0;
    if (y >= 26) { v = 150.0 / 255.0; }                         // darker rocker panel
    if (y == 17 || y == 18) { v = 1.0; }                        // pale stripe at the beltline
    return ps1_srgb_to_linear(float3(v));
}

float3 ps1_ground(float2 uv) {
    int tx = int(floor(fract(uv.x) * 32.0));
    int y = 31 - int(floor(fract(uv.y) * 32.0));
    float3 c = float3(22.0, 38.0, 58.0);
    if (tx == 0 && y == 0) { c = float3(45.0, 226.0, 230.0); }
    else if (tx == 0 || y == 0) { c = float3(42.0, 127.0, 140.0); }
    return ps1_srgb_to_linear(c / 255.0);
}

float3 ps1_wheel(float2 uv, float3 w) {
    int tx = int(floor(clamp(uv.x, 0.0, 0.9999) * 32.0));
    int y = 31 - int(floor(clamp(uv.y, 0.0, 0.9999) * 32.0));
    float dx = (float(tx) + 0.5 - 16.0) / 16.0;
    float dy = (float(y) + 0.5 - 16.0) / 16.0;
    float r = sqrt(dx * dx + dy * dy);
    float spokes = w.x;
    float lip = w.y;
    bool solid = w.z > 0.5;
    float v;
    if (r > 1.0) {
        v = 17.0;
    } else if (r >= lip) {
        v = 255.0;
    } else if (r < 0.16) {
        v = 110.0;
    } else if (solid) {
        // Steel wheel: four slots round a dished face.
        float a = atan2(dy, dx);
        float phase = fract(a / 6.2831853 * 4.0);
        v = (r >= 0.42 && r <= 0.62 && phase < 0.22) ? 34.0 : 225.0;
    } else {
        float a = atan2(dy, dx);
        float phase = fract(a / 6.2831853 * max(spokes, 1.0));
        float spokeWidth = spokes >= 10.0 ? 0.5 : 0.38;
        v = (phase < spokeWidth) ? 255.0 : 30.0;
    }
    return ps1_srgb_to_linear(float3(v / 255.0));
}

fragment float4 ps1_fragment(Ps1Out in [[stage_in]]) {
    int code = int(in.code + 0.5);
    int tex = code >> 2;
    float2 uv = in.uvw.xy / max(in.uvw.z, 0.0001);
    float3 t = float3(1.0);
    if (tex == 3) { t = ps1_ground(uv); }
    else if (tex == 2) { t = ps1_wheel(uv, in.wheel); }
    else if (tex == 1) { t = ps1_body(uv); }
    float3 c = t * in.col + in.spec;
    uint2 f = uint2(in.position.xy);
    float bayer = float(kBayer[int(f.y & 3u) * 4 + int(f.x & 3u)]) / 16.0;
    c += (bayer - 0.5) / 24.0;                                   // ordered dither
    c = pow(clamp(c, 0.0, 1.0), float3(0.8));                    // gamma lift
    c = floor(c * 31.0 + 0.5) / 31.0;                            // 15-bit colour
    return float4(c, 1.0);
}
