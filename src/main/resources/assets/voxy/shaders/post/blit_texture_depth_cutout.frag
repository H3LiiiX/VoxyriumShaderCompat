#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
#ifdef VOXY_VULKAN
layout(binding = 1, std140) uniform CompositeParams {
    mat4 invProjMat;
    mat4 projMat;
    vec4 endParams;
    vec4 fogColour;
    vec4 fadeParams;
};
#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#endif
#else
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef HAS_FOG
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
#endif
#ifdef HAS_FADE
layout(location = 6) uniform vec4 fadeParams;
#endif
#endif
#endif

#import <voxy:util/depthutils.glsl>

//Shaderpack LOD programs: whether one of the outputs is the lit colour (colortex0). Without one (a pack
// that writes only gbuffer data, Photon) colourTex holds where the LODs were drawn, nothing else
#ifndef PACK_HAS_COLOUR
#define PACK_HAS_COLOUR 1
#endif

#if PACK_HAS_COLOUR
#ifdef VOXY_VULKAN
layout(location = 0) out vec4 colour;
#else
out vec4 colour;
#endif
#define PACK_DATA_LOCATION 1
#else
vec4 colour;
#define PACK_DATA_LOCATION 0
#endif
in vec2 UV;

//Shaderpack LOD programs: the pack's other draw buffers (e.g. colortex6), written where the LOD colour
// is. Each is copied as the pack wrote it (mode 0) unless it was blended: then it holds the blended
// layers over transparent black, which go over the target the way the colour does (mode 1, an "over"
// blend) or are handed to the pack's own blend as they are (mode 2)
#ifndef PACK_DATA_COUNT
#define PACK_DATA_COUNT 0
#endif
#if PACK_DATA_COUNT > 0
layout(binding = 4) uniform sampler2D packData0;
layout(location = PACK_DATA_LOCATION + 0) out vec4 packOut0;
#endif
#if PACK_DATA_COUNT > 1
layout(binding = 5) uniform sampler2D packData1;
layout(location = PACK_DATA_LOCATION + 1) out vec4 packOut1;
#endif
#if PACK_DATA_COUNT > 2
layout(binding = 6) uniform sampler2D packData2;
layout(location = PACK_DATA_LOCATION + 2) out vec4 packOut2;
#endif
#if PACK_DATA_COUNT > 3
layout(binding = 7) uniform sampler2D packData3;
layout(location = PACK_DATA_LOCATION + 3) out vec4 packOut3;
#endif
#if PACK_DATA_COUNT > 4
layout(binding = 8) uniform sampler2D packData4;
layout(location = PACK_DATA_LOCATION + 4) out vec4 packOut4;
#endif
#if PACK_DATA_COUNT > 5
layout(binding = 9) uniform sampler2D packData5;
layout(location = PACK_DATA_LOCATION + 5) out vec4 packOut5;
#endif
#ifndef PACK_DATA_MODES
#define PACK_DATA_MODES 0
#endif

//Vitrail's coverage mask: the depth written here, so its scene seed takes LOD pixels for the pack's own
// geometry and leaves what was composited into the pack's terrain targets alone
#ifdef COVERAGE_OUT
layout(location = 1) out vec4 coverageOut;
#endif

vec4 packDataOut(sampler2D tex, int mode) {
    vec4 value = texture(tex, UV.xy);
    if (mode == 1) {
        //Un-premultiplied for the SRC_ALPHA blend, as the colour is below
        value = value.a > 0.0 ? vec4(value.rgb / value.a, value.a) : vec4(0.0);
    }
    return value;
}

//The mode of data output i, two bits each
int packDataMode(int i) {
    return (PACK_DATA_MODES >> (2 * i)) & 3;
}

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(SCREEN2NDC(clip),1.0f);
    return view.xyz/view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = projDepth(point);
    //TODO: HERE make an option/define to emit the output depth as something other then the input (i.e. if voxy is reverse z and vanilla isnt, transform and emit as not reverrse z)
    depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);
    depth = NDC2SCREEN_DEPTH(depth);

#ifndef VOXY_VULKAN
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;//TODO: dont think this is right at all so should fix this
#endif

    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #if PACK_DATA_COUNT > 0
    packOut0 = packDataOut(packData0, packDataMode(0));
    #endif
    #if PACK_DATA_COUNT > 1
    packOut1 = packDataOut(packData1, packDataMode(1));
    #endif
    #if PACK_DATA_COUNT > 2
    packOut2 = packDataOut(packData2, packDataMode(2));
    #endif
    #if PACK_DATA_COUNT > 3
    packOut3 = packDataOut(packData3, packDataMode(3));
    #endif
    #if PACK_DATA_COUNT > 4
    packOut4 = packDataOut(packData4, packDataMode(4));
    #endif
    #if PACK_DATA_COUNT > 5
    packOut5 = packDataOut(packData5, packDataMode(5));
    #endif
    #if PACK_HAS_COLOUR
    #ifdef PACK_LOD
    //A translucent LOD with no opaque LOD behind it was blended over the cleared target, so its
    // colour is premultiplied; the blend below multiplies by alpha again
    if (colour.a < 1.0) {
        colour.rgb /= colour.a;
    }
    #endif

    #ifdef HAS_FOG
    if (fogColour.a>0.0){
        //endParams.w above 1.5: the shaderpack's border fog (endParams as below) also where the LODs go
        // into the game's own image, which Vitrail paints back into the pack. Read as the linear fog
        // there, those parameters fogged every LOD whole (into solid fog colour)
        #ifdef VOXY_SHADER_FOG
        if (true) {
        #else
        if (endParams.w > 1.5) {
        #endif
        float lPos = length(point.xyz);
        float fogRel = lPos * endParams.x;
        float fog4 = (fogRel * fogRel) * (fogRel * fogRel);
        float fogLerp = clamp(1.0 - exp(-endParams.y * fog4), 0.0, 1.0);
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp * fogColour.a);
        } else {
        float fogLerp = clamp(fma(length(point.xyz),endParams.x,endParams.y),0,endParams.z);//512 is 32*16 which is the render distance in blocks
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp*fogColour.a);
        }
    }
    #endif
    #ifdef HAS_FADE
    //yes am aware how inefficent this is, this could all be packed into endParams.w
    if (fadeParams.x>0) {
        float len = fadeParams.x>1.5?length(point.xyz):length(point.xz);
        colour.a *= 1-clamp(fma(len, fadeParams.z, fadeParams.y), 0,1);
        if (colour.a <= 0.0) {
            discard;
        }
    }
    #endif
    #endif
    #ifdef COVERAGE_OUT
    coverageOut = vec4(depth, 0.0, 0.0, 1.0);
    #endif
    #ifdef KEEP_TARGET_COLOUR
    //Only the depth (and the mask) of these LODs go in: their colour is not a colour at all, and what
    // the target holds stays (zero alpha through the alpha blend)
    colour = vec4(0.0);
    #endif
    #else
    colour = vec4(0);
    #endif

}