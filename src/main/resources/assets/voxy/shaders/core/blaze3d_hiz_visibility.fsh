#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
//? endif
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat; // Complete world-to-clip matrix, including camera translation.
//? if MC_26_3
    mat4 TextureMat;
//? endif
    vec4 ColorModulator;
    vec3 ModelOffset; // source viewport width/height, zero-to-one depth
//? if MC_26_2
    mat4 TextureMat;
//? endif
};
uniform sampler2D Sampler0;
uniform samplerBuffer VoxyOcclusionOrigins;
uniform usamplerBuffer VoxyOcclusionBounds;
//? if MC_26_3
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
//? else
in vec2 texCoord;
out vec4 fragColor;
//? endif

bool visible(vec4 origin, uint bounds) {
    vec3 minimum = vec3(bounds & 31u, (bounds >> 5u) & 31u, (bounds >> 10u) & 31u);
    vec3 extent = vec3((bounds >> 15u) & 31u, (bounds >> 20u) & 31u, (bounds >> 25u) & 31u) + 1.0;
    // Include bake expansion and float rounding at very large world coordinates.
    vec3 padding = max(vec3(0.002 * origin.w), abs(origin.xyz) * 0.0000002);
    vec3 low = origin.xyz + minimum * origin.w - padding;
    vec3 high = origin.xyz + (minimum + extent) * origin.w + padding;
    vec2 first = vec2(1.0), last = vec2(0.0);
    float nearestDepth = 0.0;
    for (int corner = 0; corner < 8; corner++) {
        vec3 p = mix(low, high, bvec3((corner & 1) != 0, (corner & 2) != 0, (corner & 4) != 0));
        vec4 clip = ModelViewMat * vec4(p, 1.0);
        // A near-plane crossing or a box enclosing the eye is never safely occluded.
        if (clip.w <= 0.0) return true;
        vec3 ndc = clip.xyz / clip.w;
        float depth = ModelOffset.z > 0.5 ? ndc.z : ndc.z * 0.5 + 0.5;
        if (depth >= 1.0) return true;
        nearestDepth = max(nearestDepth, depth);
        vec2 uv = ndc.xy * 0.5 + 0.5;
        first = min(first, uv); last = max(last, uv);
    }
    if (any(lessThan(last, vec2(0.0))) || any(greaterThan(first, vec2(1.0)))) return true;
    // Include rasterization edges, then query every intersected texel at a sufficiently coarse level.
    vec2 lowPixel = clamp(first * ModelOffset.xy - 2.0, vec2(0.0), ModelOffset.xy - 1.0);
    vec2 highPixel = clamp(last * ModelOffset.xy + 2.0, vec2(0.0), ModelOffset.xy - 1.0);
    float span = max(highPixel.x - lowPixel.x, highPixel.y - lowPixel.y) / 4.0;
    int mip = max(0, int(ceil(log2(max(1.0, span)))));
    mip = min(mip, int(floor(log2(float(max(textureSize(Sampler0, 0).x, textureSize(Sampler0, 0).y))))));
    float divisor = 4.0 * exp2(float(mip));
    ivec2 a = ivec2(floor(lowPixel / divisor)), b = ivec2(floor(highPixel / divisor));
    ivec2 size = textureSize(Sampler0, mip);
    float furthest = 1.0;
    // The chosen mip makes the rectangle intersect at most two texels on either axis.
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        ivec2 p = ivec2(x == 0 ? a.x : b.x, y == 0 ? a.y : b.y);
        furthest = min(furthest, texelFetch(Sampler0, clamp(p, ivec2(0), size - 1), mip).r);
    }
    return furthest <= nearestDepth + max(0.000002, nearestDepth * 0.0005);
}
void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    int index = cell.y * 256 + cell.x;
    vec4 origin = texelFetch(VoxyOcclusionOrigins, index);
    fragColor = vec4(origin.w <= 0.0 || visible(origin, texelFetch(VoxyOcclusionBounds, index).r) ? 1.0 : 0.0);
}
