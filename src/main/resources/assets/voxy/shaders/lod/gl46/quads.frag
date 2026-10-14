#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

#ifdef VOXY_VULKAN
layout(binding = 8) uniform sampler2D blockModelAtlas;
layout(binding = 10) uniform sampler2D depthTex;
#else
layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;
#endif

//#define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif

//On non-Apple macOS GPUs, older MoltenVK/SPIRV-Cross builds can misclassify
//covered fragments while guarding stores after OpKill.  The result is a depth
//write without the matching colour write, which the SSAO pass deliberately
//visualises as red.  A zero sample mask has the same framebuffer semantics as
//discard for this single-sample pipeline without entering MoltenVK's broken
//discard/helper-thread path.  Other backends retain the original OpKill path.
#ifdef VOXY_VULKAN_SAMPLE_MASK_DISCARD
#define VOXY_DISCARD_FRAGMENT() { gl_SampleMask[0] = 0; return; }
#else
#define VOXY_DISCARD_FRAGMENT() { discard; return; }
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#endif

#if defined PATCHED_SHADER || defined ANIMATED_LAVA
//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>
#endif

#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/lighting.glsl>


#import <voxy:util/depthutils.glsl>


vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}


uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


#ifdef VOXY_SMOOTH_LIGHT
layout(location = 2) in vec3 voxyPos;
layout(location = 3) in flat vec2 voxyPlane;//lod scale, the face plane's offset along its axis
#ifdef VOXY_LIGHT_PREPASS
//The light of every visible LOD pixel and the plane its face lies on: (light map uv, face, plane offset)
layout(location = 0) out vec4 voxyLightOut;
#else
layout(binding = 6) uniform sampler2D voxyLightTex;//what the prepass wrote
#endif
#endif

#ifdef PACK_ANIMATED_WATER
//Shaderpack LOD programs: water is drawn with Minecraft's own (animated) water sprite rather than
// the frame Voxy baked into its atlas, so it moves like the water in real chunks
layout(binding = 7) uniform sampler2D voxyWaterAtlas;
layout(push_constant) uniform VoxyWaterPush { vec4 voxyWaterSprite; };//u0, v0, u1, v1 of water_still
#endif

#ifdef ANIMATED_LAVA
//Lava is drawn with Minecraft's own (animated) lava sprite rather than the frame Voxy baked into its
// atlas, so it pulses in step with the lava in real chunks instead of holding one frame
layout(binding = 7) uniform sampler2D voxyLavaAtlas;
layout(push_constant) uniform VoxyLavaPush { vec4 voxyLavaSprite; };//u0, v0, u1, v1 of lava_still

//The sprite repeats once per real block, as on real lava, rather than once per LOD block: stretched
// over a coarse LOD block its pulsing blotches showed several times their size, where real lava that
// far off is sampled from a mip that has averaged them out
vec4 voxyLavaColour(vec2 uv, vec2 dx, vec2 dy) {
    #ifdef PATCHED_SHADER
    uint lodLevel = interData.w & 7u;
    #else
    uint lodLevel = (interData.w >> 3) & 7u;
    #endif
    float blocks = float(1u << lodLevel);
    vec2 spriteSize = voxyLavaSprite.zw - voxyLavaSprite.xy;
    return vec4(textureGrad(voxyLavaAtlas, voxyLavaSprite.xy + fract(uv * blocks) * spriteSize,
                            dx * blocks * spriteSize, dy * blocks * spriteSize).rgb, 1.0);
}
#endif

#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};

void voxy_emitFragment(VoxyFragmentParameters parameters);

#if defined(VOXY_SMOOTH_LIGHT) && !defined(VOXY_LIGHT_PREPASS)
vec3 voxyAxis(uint axis) {
    return axis == 0u ? vec3(0, 1, 0) : (axis == 1u ? vec3(0, 0, 1) : vec3(1, 0, 0));
}

//An LOD face carries a single light value for the block in front of it, so torch light shows every
// block of a merged quad as a step where real chunks (vanilla smooth lighting) fade from block to
// block. Blend the light between the four nearest block centres on the face's plane, reading each from
// the prepass where that centre lands on screen, as long as it is the same face on the same plane
// (anything else, off screen, hidden or another surface, keeps this block's own light)
vec2 voxySmoothLight(vec2 own, uint face) {
    uint axis = face >> 1;
    vec3 t1 = voxyAxis((axis + 1u) % 3u);
    vec3 t2 = voxyAxis((axis + 2u) % 3u);
    float scale = voxyPlane.x;
    vec2 onPlane = vec2(dot(voxyPos, t1), dot(voxyPos, t2)) / scale;
    vec2 q = onPlane - 0.5;
    vec2 base = floor(q);
    vec2 w = q - base;
    vec2 size = vec2(textureSize(voxyLightTex, 0));
    vec2 result = vec2(0);
    for (int i = 0; i < 4; i++) {
        vec2 corner = vec2(i & 1, i >> 1);
        vec2 centre = base + corner + 0.5;
        vec3 point = voxyPos + (t1 * (centre.x - onPlane.x) + t2 * (centre.y - onPlane.y)) * scale;
        vec4 clip = MVP * vec4(point, 1.0);
        vec2 light = own;
        if (clip.w > 0.0) {
            vec2 screen = clip.xy / clip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(screen, vec2(0.0))) && all(lessThan(screen, vec2(1.0)))) {
                vec4 sampled = texelFetch(voxyLightTex, ivec2(screen * size), 0);
                if (sampled.z == float(face) && abs(sampled.w - voxyPlane.y) < 0.01 * scale) {
                    light = sampled.xy;
                }
            }
        }
        vec2 weight2 = mix(1.0 - w, w, corner);
        result += light * (weight2.x * weight2.y);
    }
    return result;
}
#endif

#ifdef VOXY_PACK_AO_LOCATION
//The face and AO flag Voxy's SSAO pass reads (the unpatched path packs them into the colour's alpha,
// which here is the pack's), in the output after the pack's own draw buffers
layout(location = VOXY_PACK_AO_LOCATION) out vec4 voxyPackAO;
#endif
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, TODO: FIXME: this is better but still not great, try encode data into the top bit of alpha so its per pixel

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


void main() {
    #ifdef VOXY_VULKAN_SAMPLE_MASK_DISCARD
    gl_SampleMask[0] = 1;
    #endif

    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) VOXY_DISCARD_FRAGMENT();
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);//vec2(lDx, dDx);
        vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }// else {
    #if defined PACK_ANIMATED_WATER || defined ANIMATED_LAVA
    //Taken here, before helper invocations leave, like the gradients above
    vec2 waterDx = dFdx(uv);
    vec2 waterDy = dFdy(uv);
    #endif
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #if !defined(PATCHED_SHADER_ALLOW_DERIVATIVES) && !defined(VOXY_VULKAN_SAMPLE_MASK_DISCARD)
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        VOXY_DISCARD_FRAGMENT();
    }

    //Check the minimum bounding texture and ensure we are greater than it
    if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {
        VOXY_DISCARD_FRAGMENT();
    }


    //Also, small quad is really fking over the mipping level somehow
    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    //if (useDiscard() && (colour.a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        //This is stupidly stupidly bad for divergence
        //TODO: FIXME, basicly what this do is sample the exact pixel (no lod) for discarding, this stops mipmapping fucking it over
        #ifndef DEBUG_RENDER
        VOXY_DISCARD_FRAGMENT();
        #endif
    }

    #if !defined(PATCHED_SHADER_ALLOW_DERIVATIVES) && !defined(VOXY_VULKAN_SAMPLE_MASK_DISCARD)
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
    #ifdef ANIMATED_LAVA
    if (modelIsLava(modelData[getModelId()])) {
        colour = voxyLavaColour(uv, waterDx, waterDy);
    }
    #endif
    colour = computeColour(texPos, colour);
    outColour = colour;

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    #ifdef ANIMATED_LAVA
    if (modelIsLava(model)) {
        colour = voxyLavaColour(uv, waterDx, waterDy);
    }
    #endif

    #ifdef PACK_ANIMATED_WATER
    if (modelIsFluid(model) && modelIsTranslucent(model)) {
        vec2 spriteSize = voxyWaterSprite.zw - voxyWaterSprite.xy;
        colour = textureGrad(voxyWaterAtlas, voxyWaterSprite.xy + fract(uv) * spriteSize, waterDx * spriteSize, waterDy * spriteSize);
    }
    #endif

    #ifdef VOXY_VULKAN
    //Minecraft's Vulkan backend rasterises with clockwise front faces (the projection Voxy shares
    // with it is not flipped for Vulkan's framebuffer), while Voxy's pipelines declare
    // counter-clockwise, so gl_FrontFacing reads the other way round from OpenGL. Taken as is, every
    // LOD normal handed to the shaderpack pointed into the block.
    bool frontFacing = !gl_FrontFacing;
    #else
    bool frontFacing = gl_FrontFacing;
    #endif
    uint face = getFace();
    face ^= uint((face&1u)!=uint(frontFacing!=((face>>1)!=0u)));
    vec2 lightUv = getLightmapUv(interData.y);
    #ifdef VOXY_LIGHT_PREPASS
    voxyLightOut = vec4(lightUv, float(face), voxyPlane.y);
    return;
    #else
    #ifdef VOXY_SMOOTH_LIGHT
    lightUv = voxySmoothLight(lightUv, face);
    #endif
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, lightUv, tint, model.customId));
    #endif
    #ifdef VOXY_PACK_AO_LOCATION
    voxyPackAO = vec4(float(face | (uint(modelIsShaded(model))<<6)) / 255.0, 0.0, 0.0, 1.0);
    #endif

    #endif
}



//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, texPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif

//Undefine the depth stuff
#import <voxy:util/depthutils.glsl>
