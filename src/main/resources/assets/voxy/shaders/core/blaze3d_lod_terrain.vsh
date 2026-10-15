#version 330
//? if MC_26_3
#extension GL_ARB_separate_shader_objects : require
#include <voxy:blaze3d_sodium_coverage.glsl>
//? else
#moj_import <voxy:blaze3d_sodium_coverage.glsl>
//? endif

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
//? if MC_26_3
    mat4 TextureMat;
//? endif
    vec4 ColorModulator;
    vec3 ModelOffset;
//? if MC_26_2
    mat4 TextureMat;
//? endif
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Only the original Cortex quad advances per instance; metadata is shared on the GPU.
//? if MC_26_3
layout(location = 0) in vec2 Corner;
layout(location = 1) in uvec2 QuadData;
//? else
in vec2 Corner;
in uvec2 QuadData;
//? endif
layout(std140) uniform VoxySection {
    vec4 SectionOriginScale;
};
layout(std140) uniform VoxyLighting {
    vec4 DirectionalShades; // up, down, north/south, east/west
    vec4 CameraWorld;
};
uniform usamplerBuffer VoxyModelFaces;
uniform usamplerBuffer VoxyModelInfo;
uniform usamplerBuffer VoxyColours;
uniform samplerBuffer VoxyOcclusionOrigins;
uniform sampler2D VoxyVisibility;

uniform sampler2D Sampler2;

//? if MC_26_3
layout(location = 0) out vec2 texCoord0;
layout(location = 1) flat out vec4 tintColor;
layout(location = 2) flat out vec4 vertexLighting;
layout(location = 3) flat out int modelId;
layout(location = 4) flat out int quadFlags;
layout(location = 5) out float sphericalDistance;
layout(location = 6) out vec2 worldXZ;
//? else
out vec2 texCoord0;
flat out vec4 tintColor;
flat out vec4 vertexLighting;
flat out int modelId;
flat out int quadFlags;
out float sphericalDistance;
out vec2 worldXZ;
//? endif

float fluidHeightOffset(int face, int axis, ivec2 corner, uint heights) {
    int index = -1;
    if (face == 1) index = (corner.x << 1) | corner.y;
    else if (axis == 1 && corner.y == 1) index = (corner.x << 1) | (face & 1);
    else if (axis == 2 && corner.x == 1) index = ((face & 1) << 1) | corner.y;
    return index < 0 ? 0.0 : float(((heights >> uint(index * 3)) & 7u) + 1u) / 8.0 - 1.0;
}

void main() {
    if (CameraWorld.w > 0.5) {
        uint h = floatBitsToUint(SectionOriginScale.x);
        h = (((h << 13u) | (h >> 19u)) ^ floatBitsToUint(SectionOriginScale.y)) * 0x9E3779B9u;
        h = (((h << 17u) | (h >> 15u)) ^ floatBitsToUint(SectionOriginScale.z)) * 0x85EBCA6Bu;
        h = (((h << 15u) | (h >> 17u)) ^ floatBitsToUint(SectionOriginScale.w)) * 0xC2B2AE35u;
        h = (h ^ (h >> 16u)) * 0x7FEB352Du;
        h = (h ^ (h >> 15u)) * 0x846CA68Bu;
        uint index = (h ^ (h >> 16u)) & 65535u;
        if (all(equal(texelFetch(VoxyOcclusionOrigins, int(index)), SectionOriginScale))
                && texelFetch(VoxyVisibility, ivec2(int(index & 255u), int(index >> 8u)), 0).r < 0.5) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
    }
    int face = int(QuadData.x & 7u);
    int axis = face >> 1;
    modelId = int((QuadData.x >> 26u) | ((QuadData.y & 1023u) << 6u));
    uvec4 info = texelFetch(VoxyModelInfo, modelId);
    uint Material = info.z;
    // Match Native's directional section rejection, preserving all double-sided and translucent models.
    if ((Material & 36u) == 0u) {
        int coordinate = axis == 0 ? 1 : axis == 1 ? 2 : 0;
        float relative = CameraWorld[coordinate] - SectionOriginScale[coordinate];
        if (((face & 1) == 0 && relative > 32.002 * SectionOriginScale.w)
                || ((face & 1) != 0 && relative < -0.002 * SectionOriginScale.w)) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
    }
    uint FaceData = face < 4 ? texelFetch(VoxyModelFaces, modelId)[face] : info[face - 4];
    bool fluid = (Material & 16u) != 0u;
    float scale = SectionOriginScale.w;
    vec2 textureMin = vec2(FaceData & 15u, (FaceData >> 8u) & 15u) / 16.0 - 0.00005;
    vec2 textureEnd = vec2((FaceData >> 4u) & 15u, (FaceData >> 12u) & 15u) / 16.0 + 1.0 / 16.0;
    vec2 size = fluid ? vec2(1.0) : vec2((QuadData.x >> 3u) & 15u, (QuadData.x >> 7u) & 15u) + 1.0;
    vec2 textureSpan = textureEnd - textureMin + size - 1.0;
    vec2 geometryMin = fluid ? vec2(0.0) : textureMin;
    vec2 geometrySize = fluid ? vec2(1.0) : textureSpan;
    uint encodedDepth = (FaceData >> 16u) & 63u;
    if (encodedDepth == 63u) encodedDepth = 64u;
    float depth = fluid ? 0.0 : float(encodedDepth) / 64.0;
    if ((face & 1) != 0) depth = 1.0 - depth;

    vec3 position = SectionOriginScale.xyz + vec3((QuadData.x >> 21u) & 31u,
            (QuadData.x >> 16u) & 31u, (QuadData.x >> 11u) & 31u) * scale;
    vec2 footprint = axis == 0 ? size : axis == 1 ? vec2(size.x, 1.0) : vec2(1.0, size.y);
    if (sodiumInteriorQuad(position.xz, position.xz + footprint * scale)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec2 offset = geometrySize * Corner * scale;
    if (axis == 0) {
        position += vec3(geometryMin.x, depth, geometryMin.y) * scale;
        position += vec3(offset.x, 0.0, offset.y);
    } else if (axis == 1) {
        position += vec3(geometryMin.x, geometryMin.y, depth) * scale;
        position += vec3(offset.x, offset.y, 0.0);
    } else {
        position += vec3(depth, geometryMin.x, geometryMin.y) * scale;
        position += vec3(0.0, offset.x, offset.y);
    }
    if (fluid) {
        uint heights = ((QuadData.x >> 3u) & 255u) | (((QuadData.y >> 10u) & 15u) << 8u);
        position.y += fluidHeightOffset(face, axis, ivec2(Corner), heights) * scale;
    }
    vec4 viewPosition = ModelViewMat * vec4(position, 1.0);
    gl_Position = ProjMat * viewPosition;
    texCoord0 = textureMin + textureSpan * Corner;
    quadFlags = int((QuadData.y >> 23u) & 255u) | (face << 8)
            | (int((FaceData >> 24u) & 3u) << 11)
            | (int(size.x - 1.0) << 15) | (int(size.y - 1.0) << 19);
    if (((FaceData >> 22u) & 1u) != 0u
            || (((FaceData >> 23u) & 1u) != 0u && (size.x > 1.0 || size.y > 1.0))) {
        quadFlags |= 1 << 13;
    }
    if ((Material & 4u) != 0u) quadFlags |= 1 << 14;
    int packedLight = quadFlags & 0xFF;
    vec2 lightUv = clamp(vec2((packedLight >> 4) & 0xF, packedLight & 0xF) / 16.0
            + (0.5 / 16.0), vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    float directionalShade = DirectionalShades.x;
    if ((Material & 8u) != 0u) {
        directionalShade = axis == 1 ? DirectionalShades.z : axis == 2 ? DirectionalShades.w
                : face == 1 ? DirectionalShades.x : DirectionalShades.y;
    }
    uint tint = info.w;
    if ((Material & 2u) != 0u) {
        uint index = tint + ((QuadData.y >> 14u) & 511u);
        tint = index < uint(textureSize(VoxyColours)) ? texelFetch(VoxyColours, int(index)).r : 0xFFFFFFFFu;
    }
    tintColor = vec4(vec3((tint >> 16u) & 255u, (tint >> 8u) & 255u, tint & 255u) / 255.0, 1.0);
    vertexLighting = texture(Sampler2, lightUv) * vec4(vec3(directionalShade), 1.0);
    sphericalDistance = length(viewPosition.xyz);
    worldXZ = position.xz;
}
