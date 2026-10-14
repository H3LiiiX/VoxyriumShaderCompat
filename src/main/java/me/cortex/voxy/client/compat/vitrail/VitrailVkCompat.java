package me.cortex.voxy.client.compat.vitrail;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class VitrailVkCompat {
    private static final boolean IS_LOADED = FabricLoader.getInstance().isModLoaded("vitrail");
    private static boolean initialized = false;
    private static Method drawingPackMethod;
    private static Field activeField;
    private static Field targetsField;
    private static Method viewMethod;
    private static Object sideMain;

    private static Method flushGeometryHoldMethod;
    private static final java.util.function.Supplier<String> VOXY_HOLD_CAUSE = () -> "Voxy LOD frame";

    private static Method sessionMethod;
    private static Method settingsMethod;
    private static Method chosenMethod;
    private static Method asBooleanMethod;

    //The thread currently inside Vitrail's shadow stage (ShadowTerrain.draw), or null.
    // Vitrail draws its shadow map at the END of every level frame: it re-walks Sodium's visibility
    // graph for the light (RenderSectionManager.finalizeRenderLists with the light's frustum) and then
    // calls SodiumWorldRenderer.drawChunkLayer for the OPAQUE group with the light's matrices. To Voxy's
    // Sodium hooks that is indistinguishable from the camera's own walk/draw (Vitrail is not Iris, so
    // IrisUtil.irisShadowActive() was false), so it overwrote the visible-section stream with the
    // sun's view and re-armed the pending VK frame capture with the light's matrices.
    // Keyed on the thread so an asynchronous camera walk on another thread is never mistaken for it.
    private static volatile Thread shadowStageThread;

    private VitrailVkCompat() {}

    public static void enterShadowStage() {
        shadowStageThread = Thread.currentThread();
    }

    public static void exitShadowStage() {
        shadowStageThread = null;
    }

    /** True only on the thread that is currently inside Vitrail's shadow stage. */
    public static boolean isShadowStageActive() {
        Thread t = shadowStageThread;
        return t != null && t == Thread.currentThread();
    }

    private static synchronized void init() {
        if (!IS_LOADED || initialized) return;
        initialized = true;
        try {
            Class<?> packChainClass = Class.forName("dev.vitrail.render.PackChain");
            Class<?> colorTargetsClass = Class.forName("dev.vitrail.render.ColorTargets");
            Class<?> sideClass = Class.forName("dev.vitrail.pack.target.TargetSchedule$Side");

            drawingPackMethod = packChainClass.getMethod("drawingPack");

            activeField = packChainClass.getDeclaredField("active");
            activeField.setAccessible(true);

            targetsField = packChainClass.getDeclaredField("targets");
            targetsField.setAccessible(true);

            viewMethod = colorTargetsClass.getDeclaredMethod("view", int.class, sideClass);
            viewMethod.setAccessible(true);

            for (Object constant : sideClass.getEnumConstants()) {
                if ("MAIN".equals(constant.toString())) {
                    sideMain = constant;
                    break;
                }
            }

            try {
                flushGeometryHoldMethod = Class.forName("dev.vitrail.render.GeometryHold").getMethod("flushIdle", java.util.function.Supplier.class);
            } catch (Throwable t) {
                Logger.warn("Failed to find Vitrail GeometryHold.flushIdle", t);
            }

            try {
                Class<?> packChoiceClass = Class.forName("dev.vitrail.render.PackChoice");
                Class<?> packSessionClass = Class.forName("dev.vitrail.settings.PackSession");
                Class<?> resolvedClass = Class.forName("dev.vitrail.settings.SettingsLayers$Resolved");
                Class<?> optionValueClass = Class.forName("dev.vitrail.pack.option.OptionValue");

                sessionMethod = packChoiceClass.getMethod("session");
                settingsMethod = packSessionClass.getMethod("settings");
                chosenMethod = resolvedClass.getMethod("chosen");
                asBooleanMethod = optionValueClass.getMethod("asBoolean");
            } catch (Throwable t) {
                Logger.warn("Failed to initialize Vitrail pack settings reflection", t);
            }

            Logger.info("Vitrail Vulkan compatibility initialized");
        } catch (Throwable t) {
            Logger.error("Failed to initialize Vitrail Vulkan compatibility", t);
            drawingPackMethod = null;
            activeField = null;
            targetsField = null;
            viewMethod = null;
            sideMain = null;
        }
    }

    public static boolean isDrawingPack() {
        if (!IS_LOADED) return false;
        if (!initialized) init();
        if (drawingPackMethod == null) return false;
        try {
            return (boolean) drawingPackMethod.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    //Vitrail keeps a "geometry hold" render pass open across consecutive chunk/entity/particle draws
    // (GeometryHold), which suspendLevelPass does not end. Whichever family drew last leaves its hold open,
    // so at afterOpaqueFeatures a pass was still recording on some frames (depending on what was on screen)
    // and the Voxy frame could not be recorded before the pack's deferred stage. flushIdle ends the hold
    // only while no family is mid-draw, which is Vitrail's own door for exactly this
    public static void endGeometryHold() {
        if (!IS_LOADED) return;
        if (!initialized) init();
        if (flushGeometryHoldMethod == null) return;
        try {
            flushGeometryHoldMethod.invoke(null, VOXY_HOLD_CAUSE);
        } catch (Throwable t) {
            Logger.error("Vitrail GeometryHold.flushIdle failed", t);
        }
    }

    public static boolean isBorderFogEnabled() {
        if (!IS_LOADED) return true;
        if (!initialized) init();
        //The border fog is Complementary's, drawn over the LODs in its place: any other pack whose own
        // LOD programs light the LODs fogs them itself
        if (Boolean.FALSE.equals(VitrailVoxyPack.loadedHasComplementaryFog())) {
            return false;
        }
        if (sessionMethod == null || settingsMethod == null || chosenMethod == null || asBooleanMethod == null) {
            return true;
        }
        try {
            Optional<?> opt = (Optional<?>) sessionMethod.invoke(null);
            if (opt == null || opt.isEmpty()) {
                return true;
            }
            Object session = opt.get();
            Object resolved = settingsMethod.invoke(session);
            if (resolved == null) {
                return true;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> chosen = (Map<String, Object>) chosenMethod.invoke(resolved);
            if (chosen == null) {
                return true;
            }
            Object borderFogVal = chosen.get("BORDER_FOG");
            if (borderFogVal == null) {
                // Complementary's default is #define BORDER_FOG (enabled)
                return true;
            }
            String str = borderFogVal.toString();
            if ("false".equalsIgnoreCase(str) || "off".equalsIgnoreCase(str) || "0".equals(str)) {
                return false;
            }
            return (boolean) asBooleanMethod.invoke(borderFogVal);
        } catch (Throwable t) {
            return true;
        }
    }

    public static float getVoxyRenderDistanceBlocks() {
        float vanillaRd = VoxyRenderSystem.getVanillaRenderDistance();
        return Math.max(vanillaRd, VoxyConfig.CONFIG.sectionRenderDistance * 512.0f);
    }

    public static List<String> patchShaderLines(String relativePath, List<String> lines) {
        if (relativePath == null || lines == null || lines.isEmpty()) {
            return lines;
        }
        String normPath = relativePath.replace('\\', '/');
        if (normPath.endsWith("lib/common.glsl")) {
            return patchCommonGlsl(lines);
        } else if (normPath.endsWith("lib/atmospherics/fog/mainFog.glsl")) {
            return patchMainFogGlsl(lines);
        } else if (normPath.endsWith("lib/materials/materialMethods/reflections.glsl")) {
            return patchReflectionsGlsl(lines);
        } else if (normPath.endsWith("lib/materials/materialMethods/wavingBlocks.glsl")) {
            return patchWavingBlocksGlsl(lines);
        } else if (normPath.endsWith("lib/lighting/shadowSampling.glsl")) {
            return patchShadowSamplingGlsl(lines);
        } else if (normPath.endsWith("lib/misc/worldOutline.glsl")) {
            return patchWorldOutlineGlsl(lines);
        } else if (normPath.endsWith("lib/materials/specificMaterials/translucents/water.glsl")) {
            return patchWaterGlsl(lines);
        } else if (normPath.endsWith("voxy_opaque.glsl") || normPath.endsWith("voxy_translucent.glsl")) {
            return defineVoxy(lines, normPath.endsWith("voxy_translucent.glsl"));
        } else if (normPath.endsWith("lib/atmospherics/fog.glsl") || normPath.endsWith("lib/atmospherics/clouds.glsl")
                || normPath.endsWith("lib/atmosphere/fog.glsl")) {
            //BSL's fog and cloud fog, Solas's fog
            return patchFarFogForLods(lines);
        } else if (normPath.endsWith("lib/atmospherics/volumetricLight/volumetricLight.glsl")) {
            return patchComplementaryLodVolumetricLight(lines);
        } else if (normPath.endsWith("lib/vertex/waving.glsl")) {
            return patchBslLavaWaves(lines);
        } else if (normPath.endsWith("program/gbuffers_all_solid.vsh")) {
            return patchPhotonLavaAo(lines);
        } else if (normPath.endsWith("include/fog/simple_fog.glsl")) {
            //Photon's border fog
            return patchPhotonBorderFog(lines);
        } else if (normPath.endsWith("gbuffers_water.glsl") || normPath.endsWith("include/vertex/displacement.glsl")) {
            return patchWaterWaveFade(lines);
        } else if (normPath.endsWith("include/lighting/diffuse_lighting.glsl")) {
            //Photon's diffuse lighting: the shadowed one for its LOD translucents too (see patchPhotonLodWater)
            List<String> result = null;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("#if defined SHADOW || defined SHADOW_SSRT")) {
                    if (result == null) result = new ArrayList<>(lines);
                    result.set(i, "#if defined SHADOW || defined SHADOW_SSRT || defined VOXY_PACK_SHADOW_LIGHTING");
                }
            }
            return result == null ? lines : result;
        } else if (normPath.endsWith("include/misc/raytracer.glsl")) {
            return patchPhotonLodWaterRaytracer(lines);
        } else if (normPath.endsWith("lib/pbr/raytracer.glsl")) {
            //Solas's reflections march the ray through the game's projection and drop a hit whose depth
            // falls past its far plane. The LOD water lies past it, and so does everything its rays reach,
            // so it reflected nothing but sky: at sunset a pink band beside the real water, whose rays find
            // the LOD hills (they sit in the game's depth just inside that plane). The LOD water marches
            // through Voxy's projection instead, with Voxy's depth, as BSL's own LOD water does
            for (String line : lines) {
                if (line.contains("VOXY_TRANSLUCENT_AFTER_DEFERRED")) {
                    return lines;//already patched (Vitrail hands cached lines through again)
                }
            }
            List<String> result = new ArrayList<>(lines.size() + 8);
            boolean patched = false;
            for (String line : lines) {
                String trimmed = line.trim();
                String voxy = null;
                if (trimmed.equals("pos = nvec3(gbufferProjection * nvec4(viewPos)) * 0.5 + 0.5;")) {
                    voxy = line.replace("gbufferProjection", "vxProj");
                } else if (trimmed.equals("vec3 hitViewPos = nvec3(gbufferProjectionInverse * nvec4(vec3(pos.xy, sampleDepth) * 2.0 - 1.0));")) {
                    voxy = line.replace("gbufferProjectionInverse", "vxProjInv");
                }
                if (voxy != null) {
                    result.add("        #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                    result.add(voxy);
                    result.add("        #else");
                    result.add(line);
                    result.add("        #endif");
                    patched = true;
                    continue;
                }
                result.add(line);
            }
            return patched ? result : lines;
        } else if (normPath.endsWith("lib/reflections/raytrace.glsl")) {
            //BSL's reflections. Its LOD water reprojects the reflected point into last frame's view, because
            // under Iris it is drawn before the deferred stage and reads last frame's picture; drawn after it
            // here (VOXY_TRANSLUCENT_AFTER_DEFERRED) the picture it reads is this frame's, as the real water's is
            List<String> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                if (line.trim().equals("#if defined REFLECTION_PREVIOUS || defined VOXY_PATCH")) {
                    line = line.replace("#if defined REFLECTION_PREVIOUS || defined VOXY_PATCH",
                            "#if defined REFLECTION_PREVIOUS || (defined VOXY_PATCH && !defined VOXY_TRANSLUCENT_AFTER_DEFERRED)");
                }
                result.add(line);
            }
            return result;
        } else if (normPath.endsWith("voxy.json")) {
            //Solas's third LOD water output goes to colortex7, which only its Voxy branch of the deferred
            // stage reads; under Vitrail it goes to colortex3 instead, where its real water writes the
            // refraction data its refraction pass reads (see patchSolasLodWater for what is written there)
            List<String> result = null;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("\"translucentDrawBuffers\": [0, 1, 7],")) {
                    if (result == null) result = new ArrayList<>(lines);
                    result.set(i, lines.get(i).replace("[0, 1, 7]", "[0, 1, 3]"));
                }
                //Complementary's LOD terrain also writes colortex4, the normal its real terrain leaves there
                // (see defineVoxy)
                if (lines.get(i).trim().equals("\"opaqueDrawBuffers\": [") && i + 3 < lines.size()
                        && lines.get(i + 1).trim().equals("0,") && lines.get(i + 2).trim().equals("6")
                        && lines.get(i + 3).trim().startsWith("]")) {
                    if (result == null) result = new ArrayList<>(lines);
                    result.set(i + 2, lines.get(i + 2).replace("6", "6, 4"));
                }
            }
            return result == null ? lines : result;
        } else if (normPath.endsWith("programs/voxyOpaque.glsl")) {
            //Solas's LOD terrain writes colortex3 the way its Voxy branch of the deferred stage reads it
            // (normal, shadow mask, smoothness). Vitrail builds that branch out and every later stage reads
            // colortex3 the way the real terrain writes it, and its refraction pass takes a blue channel of
            // 0.8 for the water mask: wherever an LOD seabed block's shadow mask landed on 0.8 the water over
            // it was refracted, block by block, rectangles along the LOD water. The real terrain's encoding
            List<String> result = new ArrayList<>(lines.size() + 4);
            for (String line : lines) {
                if (line.trim().equals("out1 = vec4(encodeNormal(newNormal), shadowMask, clamp(fmix(smoothness, 1.0, metalness * metalness), 0.0, 0.95));")) {
                    String indent = line.substring(0, line.indexOf('o'));
                    result.add("    #if defined PBR || defined GENERATED_SPECULAR");
                    result.add(indent + "out1 = vec4(clamp(fmix(smoothness, 1.0, metalness * metalness), 0.0, 0.95), lightmap.y * 0.5, 0.0, 1.0);");
                    result.add("    #else");
                    result.add(indent + "out1 = vec4(0.0, lightmap.y * 0.5, 0.0, 1.0);");
                    result.add("    #endif");
                    continue;
                }
                result.add(line);
            }
            return result;
        } else if (normPath.endsWith("programs/voxyTranslucent.glsl")) {
            //Solas's LOD water (included by its voxy_translucent.glsl)
            return patchSolasLodWater(lines);
        }
        return lines;
    }

    //With its SSAO or screen space shadows on (both are by default) Solas's LOD water leaves colortex0
    // empty and is merged into the scene by the pack's Voxy branch of its deferred stage, which Vitrail
    // builds out: the LOD water was nowhere. Drawn after the deferred stage, where the real water is
    // (VOXY_TRANSLUCENT_AFTER_DEFERRED), it writes colortex0 as the real water does
    private static List<String> patchSolasLodWater(List<String> lines) {
        for (String line : lines) {
            if (line.contains("VOXY_TRANSLUCENT_AFTER_DEFERRED")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 16);
        boolean outPatched = false, fogIncluded = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            //Solas's real water fogs itself (gbuffers_water calls Fog), the land is fogged by the deferred
            // stage; the LOD water was merged in that stage under Iris and fogged with it, so drawn after it
            // here it was the one thing left unfogged, a clear band beside the hazy real water at the border
            if (!fogIncluded && trimmed.equals("#if WATER_NORMALS > 0")) {
                result.add("#include \"/lib/atmosphere/fog.glsl\"");
                fogIncluded = true;
            }
            if (!outPatched && trimmed.equals("#if !defined SSAO && !defined SS_SHADOWS")
                    && i + 1 < lines.size() && lines.get(i + 1).trim().equals("out0 = albedo;")) {
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("    #if defined OVERWORLD");
                result.add("    float voxyAtmosphereMix = 0.0;");
                result.add("    vec3 voxyAtmosphere = getAtmosphere(viewPos.xyz, worldPos.xyz, voxyAtmosphereMix);");
                result.add("    #elif defined NETHER");
                result.add("    vec3 voxyAtmosphere = netherColSqrt.rgb * 0.25;");
                result.add("    #else");
                result.add("    vec3 voxyAtmosphere = endAmbientColSqrt * 0.175;");
                result.add("    #endif");
                result.add("    Fog(albedo.rgb, viewPos, voxyAtmosphere, 0.0);");
                result.add("    #endif");
                line = line.replace("#if !defined SSAO && !defined SS_SHADOWS",
                        "#if (!defined SSAO && !defined SS_SHADOWS) || defined VOXY_TRANSLUCENT_AFTER_DEFERRED");
                outPatched = true;
            }
            //Its third output, colortex3 here (see the voxy.json patch): the refraction data the real water
            // writes there. The refraction pass bends the view through every water pixel by the vector it
            // finds in colortex3, and without this the LOD water left the seabed's own data there, so the
            // view through it jumped about as the camera moved. No vector (the LOD water has no refraction
            // of its own), and the water mask, so real water beside it can refract into it
            //The real water's sun and moon visibility (gbuffers_water's, and its deferred stage's that merged
            // the LOD water under Iris): it turns to night as the sun sinks a few degrees under the horizon,
            // where the terrain's, which this program had, keeps the sunset light for a while longer. After
            // sunset the LOD water was lit and fogged pink beside the dark real water
            String visibility = SOLAS_REAL_WATER_VISIBILITY.get(trimmed);
            if (visibility != null) {
                result.add("#ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(visibility);
                result.add("#else");
                result.add(line);
                result.add("#endif");
                continue;
            }
            //Its specular highlight is a rougher, unclamped one at the full light colour, where the real
            // water's (gbuffers_water) is a tight one at a light that drops to under half at night: a
            // different glint either side of the border. The real water's, term for term
            String specular = SOLAS_REAL_WATER_SPECULAR.get(trimmed);
            if (specular != null) {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(indent + specular);
                result.add("    #else");
                result.add(line);
                result.add("    #endif");
                continue;
            }
            //The real water reflects the sky only where it has full sky light, and under a sheet of ice it
            // has it (the game lights its surface from the ice above), where the LOD water takes the ice's
            // own sky light, a level or two short: it reflected nothing but its own dark colour, and LOD ice
            // was darker and more see-through than the real ice. Water within two levels of full sky light
            // reflects the sky as the real water there does
            if (trimmed.equals("getReflection(albedo, viewPos, newNormal, fresnel, lightmap.y);")) {
                result.add("\t\t#ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(line.replace("lightmap.y)", "water > 0.5 && lightmap.y > 0.85 ? 1.0 : lightmap.y)"));
                result.add("\t\t#else");
                result.add(line);
                result.add("\t\t#endif");
                continue;
            }
            //The strength of the reflection (its fresnel, which also decides how opaque the water is) comes
            // from the flat surface for the real water and from the wave normal for this one, so the sky it
            // reflects came and went with the waves: faint ripples on LOD ice that the real ice has none of.
            // The real water's, from the flat surface
            if (trimmed.equals("float fresnel = clamp(1.0 + dot(normalize(newNormal), nViewPos), 0.0, 1.0) * snellWindow;")) {
                result.add("\t\t#ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(line.replace("normalize(newNormal)", "normalize(normal)"));
                result.add("\t\t#else");
                result.add(line);
                result.add("\t\t#endif");
                continue;
            }
            if (outPatched && trimmed.equals("out2 = albedo;")) {
                String indent = line.substring(0, line.indexOf('o'));
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(indent + "out2 = vec4(0.0, 0.0, water * 0.4 + 0.4, 1.0);");
                result.add("    #else");
                result.add(line);
                result.add("    #endif");
                continue;
            }
            result.add(line);
        }
        return outPatched ? result : lines;
    }

    private static final Map<String, String> SOLAS_REAL_WATER_VISIBILITY = Map.of(
            "float sunVisibility = clamp((dot( sunVec, upVec) + 0.15) * 3.0, 0.0, 1.0);",
            "float sunVisibility = clamp(dot(sunVec, upVec) + 0.0625, 0.0, 0.125) / 0.125;",
            "float moonVisibility = clamp((dot(-sunVec, upVec) + 0.15) * 3.0, 0.0, 1.0);",
            "float moonVisibility = clamp(dot(-sunVec, upVec) + 0.0625, 0.0, 0.125) / 0.125;");

    private static final Map<String, String> SOLAS_REAL_WATER_SPECULAR = Map.of(
            "float smoothnessF = 0.6 + length(albedo.rgb) * 0.2 * float(ice > 0.5 || water > 0.5);",
            "float smoothnessF = 0.82 + 0.08 * float(ice > 0.5 || water > 0.5);",
            "albedo.rgb, waterF0, lightColSqrt, shadow * vanillaDiffuse, voxyColor.a);",
            "albedo.rgb, waterF0, lightColSqrt * (0.45 + timeBrightness * 1.65), shadow * vanillaDiffuse, voxyColor.a);",
            "albedo.rgb, waterF0, endLightCol * 0.5, shadow * vanillaDiffuse, voxyColor.a);",
            "albedo.rgb, waterF0, endLightCol * 0.35, shadow * vanillaDiffuse, voxyColor.a);",
            "albedo.rgb += specularHighlight * 0.5;",
            "albedo.rgb += clamp(specularHighlight, vec3(0.0), vec3(1.0)) * mix(0.6, 1.0, clamp(albedo.a, 0.0, 1.0));");

    //Photon draws its LOD water in its final blend pass under Iris (draw_distant_water, behind LOD_MOD_ACTIVE,
    // from the gbuffer data its LOD program writes into colortex16): the LOD program itself only adds a
    // faint highlight, so under Vitrail, which builds that branch out, the LOD water was all but invisible.
    // The LOD program draws it itself instead, with the same function and the same inputs, and puts its
    // own output over it the way the blend pass would have
    private static boolean isPhotonLodWater(List<String> lines) {
        boolean material = false, waterNormal = false, fog = false;
        for (String line : lines) {
            String trimmed = line.trim();
            material |= trimmed.equals("uint material_mask = max(parameters.customId - 10000u, 0u);");
            waterNormal |= trimmed.equals("#include \"/include/surface/water_normal.glsl\"");
            fog |= trimmed.equals("fragment_color.rgb = fragment_color.rgb * fog.a + fog.rgb;");
        }
        return material && waterNormal && fog;
    }

    private static List<String> patchPhotonLodWater(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size() + 40);
        for (String line : lines) {
            String trimmed = line.trim();
            //The program turns the pack's shadows off for itself (it has no shadow map to read), and with
            // them its diffuse lighting falls back to the one Photon uses when shadows are disabled, a
            // flatter one with a strong glow for ice. The pack's real translucents (and its LOD terrain,
            // lit in the deferred stage) take the shadowed one, so with shadows on LOD ice was whiter
            // than the real ice beside it. The shadowed lighting, with what the pack hands a surface
            // past its shadow map: the lightmap's shadow, no light through the surface from the map
            if (trimmed.equals("#undef SHADOW")) {
                result.add("#ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("#define VOXY_PACK_SHADOW_LIGHTING");
                result.add("#endif");
            }
            if (trimmed.equals("vec3 shadows = vec3(pow8(parameters.lightMap.y)); // Fake shadow")) {
                result.add("#ifdef VOXY_PACK_SHADOW_LIGHTING");
                result.add("    vec3 shadows = vec3(smoothstep(13.5 / 15.0, 14.5 / 15.0, parameters.lightMap.y));");
                result.add("#else");
                result.add(line);
                result.add("#endif");
                continue;
            }
            if (trimmed.equals("#define shadow_distance_fade 0.0")) {
                result.add("#ifdef VOXY_PACK_SHADOW_LIGHTING");
                result.add("#define shadow_distance_fade 1.0");
                result.add("#else");
                result.add(line);
                result.add("#endif");
                continue;
            }
            if (trimmed.equals("layout(location = 0) out vec4 fragment_color;")) {
                result.add("#include \"/include/misc/distant_water.glsl\"");
            }
            if (trimmed.equals("if (max_of(material.albedo * alpha) < eps) {")) {
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("    if (max_of(material.albedo * alpha) < eps && material_mask != MATERIAL_WATER) {");
                result.add("    #else");
                result.add(line);
                result.add("    #endif");
                continue;
            }
            result.add(line);
            //Its blend takes the colour premultiplied (ONE, ONE_MINUS_SRC_ALPHA), as the real translucents
            // write it (their lighting times the alpha), but for everything but water this program left
            // the alpha out: LOD ice went in at its full lit colour, brighter than the real ice
            //Its real translucents then add the sun's highlight and the reflections (ice, glass), which this
            // program left out for all but water, so LOD ice reflected nothing beside the real ice
            if (trimmed.equals("fragment_color.a = alpha;")) {
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("    if (material_mask != MATERIAL_WATER) {");
                result.add("        fragment_color.rgb *= alpha;");
                result.add("        #if defined WORLD_OVERWORLD || defined WORLD_END");
                result.add("        fragment_color.rgb += get_specular_highlight(material, NoL, NoV, NoH, LoV, LoH) * light_color * shadows;");
                result.add("        #endif");
                result.add("        #if defined ENVIRONMENT_REFLECTIONS || defined SKY_REFLECTIONS");
                result.add("        if (material.ssr_multiplier > eps) {");
                result.add("            mat3 voxyTbn = get_tbn_matrix(normal);");
                result.add("            fragment_color.rgb += get_specular_reflections(material, voxyTbn, pos_screen, pos_view,");
                result.add("                pos_scene + cameraPosition, normal, normal, dir_world, dir_world * voxyTbn,");
                result.add("                parameters.lightMap.y, false);");
                result.add("        }");
                result.add("        #endif");
                result.add("    }");
                result.add("    #endif");
            }
            if (trimmed.equals("fragment_color.rgb = fragment_color.rgb * fog.a + fog.rgb;")) {
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("    if (material_mask == MATERIAL_WATER) {");
                result.add("        vec4 voxyWater = draw_distant_water(pos_screen, pos_view, pos_scene + cameraPosition, dir_world,");
                result.add("            normal, parameters.tinting.rgb, parameters.lightMap, length(pos_scene), layer_dist);");
                result.add("        fragment_color = vec4(fragment_color.rgb + voxyWater.rgb * (1.0 - fragment_color.a),");
                result.add("            fragment_color.a + voxyWater.a * (1.0 - fragment_color.a));");
                result.add("    }");
                result.add("    #endif");
            }
        }
        return result;
    }

    private static final java.util.regex.Pattern DEFINED_VOXY = java.util.regex.Pattern.compile("\\bdefined\\s+VOXY\\b(?!_)");

    //The LODs reach the pack's other stages through the game's depth, which ends at the vanilla far
    // plane, and those stages fog the land out where the vanilla render distance ends unless VOXY (or
    // DISTANT_HORIZONS) moves that edge out to the LODs' own render distance. Under Vitrail VOXY never
    // holds for them, so every LOD vanished into the fog (BSL in the Nether and End, Solas's distant
    // fade everywhere). Their Voxy branches are taken for VOXY_LOD too, with Voxy's render distance in
    // blocks for vxRenderDistance * 16, which those programs have no uniform for. Only branches that
    // read nothing else of Voxy's are: the rest need the LODs' own depth, which only Iris hands them.
    // The pack's own LOD programs (VOXY) keep the original line
    private static List<String> patchFarFogForLods(List<String> lines) {
        for (String line : lines) {
            if (line.contains("VOXY_LOD")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 16);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            boolean ifdef = trimmed.equals("#ifdef VOXY");
            boolean ifExpr = trimmed.startsWith("#if ") && DEFINED_VOXY.matcher(trimmed).find();
            if (!ifdef && !ifExpr) {
                result.add(line);
                continue;
            }
            //The branch: up to the next directive, which has to close it
            int end = i + 1;
            while (end < lines.size() && !lines.get(end).trim().startsWith("#")) end++;
            if (end >= lines.size() || !lines.get(end).trim().equals("#endif")) {
                result.add(line);
                continue;
            }
            List<String> body = lines.subList(i + 1, end);
            boolean onlyRenderDistance = true;
            for (String b : body) {
                if (b.replace("vxRenderDistance", "").matches(".*\\bvx[A-Z]\\w*.*")) {
                    onlyRenderDistance = false;
                }
            }
            if (!onlyRenderDistance) {
                result.add(line);
                continue;
            }
            boolean readsDistance = body.stream().anyMatch(b -> b.contains("vxRenderDistance"));
            String indent = line.substring(0, line.indexOf('#'));
            if (!readsDistance) {
                //Nothing of Voxy's in it: the LODs simply take the same branch
                result.add(DEFINED_VOXY.matcher(line).replaceAll("(defined VOXY || defined VOXY_LOD)"));
                result.addAll(body);
            } else if (ifdef) {
                result.add(line);
                result.addAll(body);
                result.add(indent + "#elif defined VOXY_LOD");
                for (String b : body) {
                    result.add(b.replace("vxRenderDistance * 16.0", "float(VOXY_RENDER_DISTANCE)"));
                }
            } else {
                result.add(line);
                result.addAll(body);
            }
            result.add(lines.get(end));
            i = end;
        }
        return result;
    }

    //Real water waves up and down while LOD water is flat, so where the two meet at the render distance
    // the edge of the real water follows the wave. BSL (WavingWater in gbuffers_water) and Photon
    // (get_water_displacement) already ease their waves out towards the render distance for Voxy and
    // Distant Horizons, behind a symbol that never holds under Vitrail; Complementary's is patched in
    // patchWavingBlocksGlsl. Those branches read only far and the camera, so VOXY_LOD takes them as is.
    // Each is matched with the line after it, so no other pack's water is touched
    private static List<String> patchWaterWaveFade(List<String> lines) {
        List<String> result = null;
        for (int i = 0; i + 1 < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            String next = lines.get(i + 1).trim();
            String replaced = null;
            if (trimmed.equals("#if defined DISTANT_HORIZONS || defined VOXY")
                    && next.equals("wave *= smoothstep(far + 4.0, far - 12.0, length(viewLength));")) {
                replaced = "#if defined DISTANT_HORIZONS || defined VOXY || defined VOXY_LOD";
            } else if (trimmed.equals("#ifdef LOD_MOD_ACTIVE")
                    && next.startsWith("// Attenuate displacement towards the edge of the render distance")) {
                replaced = "#if defined LOD_MOD_ACTIVE || defined VOXY_LOD";
            }
            if (replaced != null) {
                if (result == null) result = new ArrayList<>(lines);
                String line = lines.get(i);
                result.set(i, line.substring(0, line.indexOf('#')) + replaced);
            }
        }
        return result == null ? lines : result;
    }

    //Complementary's LOD water reflects the End's sky with the volumetric light of its composite stage
    // (reflectionBackground.glsl includes it for VOXY_TRANSLUCENT), which reads the entities' depth at
    // texCoord, the full screen passes' screen coordinate. The LOD program has no texCoord, so in the End
    // its LOD programs failed to build. It reads the same buffer at the fragment's screen position
    private static List<String> patchComplementaryLodVolumetricLight(List<String> lines) {
        for (String line : lines) {
            if (line.contains("gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r;")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 4);
        boolean patched = false;
        for (String line : lines) {
            if (!patched && line.trim().equals("float entityLinearDepth = texture2D(colortex13, texCoord).r;")) {
                result.add("        #ifdef VOXY_TRANSLUCENT");
                result.add(line.replace("texCoord", "gl_FragCoord.xy / vec2(viewWidth, viewHeight)"));
                result.add("        #else");
                result.add(line);
                result.add("        #endif");
                patched = true;
                continue;
            }
            result.add(line);
        }
        return patched ? result : lines;
    }

    //BSL waves its lava surface up and down, and unlike its water (WavingWater in gbuffers_water) never
    // eases the waves out towards the render distance for LODs, so the edge of the real lava rose and fell
    // against the flat LOD lava: a wave shaped seam. The waves now ease out over the last chunk, as the water's
    private static List<String> patchBslLavaWaves(List<String> lines) {
        for (String line : lines) {
            if (line.contains("VOXY_LAVA_WAVE_FADE")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 8);
        boolean inLava = false, patched = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!patched && trimmed.equals("float CalcLavaMove(vec3 worldPos) {")) {
                result.add("#ifdef VOXY_LOD");
                result.add("#define VOXY_LAVA_WAVE_FADE");
                result.add("uniform float far;");
                result.add("#endif");
                inLava = true;
            } else if (inLava && trimmed.startsWith("return wave * 0.0125")) {
                String indent = line.substring(0, line.indexOf('r'));
                result.add("#ifdef VOXY_LAVA_WAVE_FADE");
                result.add(indent + "wave *= smoothstep(far + 4.0, far - 12.0, length(worldPos - cameraPosition));");
                result.add("#endif");
                inLava = false;
                patched = true;
            }
            result.add(line);
        }
        return patched ? result : lines;
    }

    //Photon darkens its terrain by the vanilla ambient occlusion in the vertex colour, which Sodium gives
    // lava too (the game itself never shades fluids so). Its LOD program has none, so the real lava was a
    // shade darker than the LOD lava beside it, a seam at the render distance. Lava goes without, as the
    // pack's leaves already do
    private static List<String> patchPhotonLavaAo(List<String> lines) {
        for (String line : lines) {
            if (line.contains("/* MATERIAL_LAVA */")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 4);
        boolean patched = false;
        for (String line : lines) {
            result.add(line);
            if (!patched && line.trim().equals("material_mask == 5 ? 1.0 : vanilla_ao; // no vanilla ao on leaves")) {
                String indent = line.substring(0, line.indexOf('m')).replaceFirst("    $", "");
                result.add("#ifdef VOXY_LOD");
                result.add(indent + "vanilla_ao = material_mask == 39 /* MATERIAL_LAVA */ ? 1.0 : vanilla_ao;");
                result.add("#endif");
                patched = true;
            }
        }
        return result;
    }

    //Photon's reflections march through the combined depth buffer (colortex15), which only its Voxy branch
    // under Iris fills, so under Vitrail the LOD water found nothing in it and reflected only the sky: no
    // shore or trees in it where the real water beside it showed them. The LOD water marches through
    // Voxy's own depth instead (vxDepthTexOpaque, which carries the real chunks too) with Voxy's
    // projection, as BSL's and Solas's LOD water do. That depth has no hand in it, and what lies before
    // Voxy's near plane sits at 0, so only that ends a hit, not Photon's hand depth
    private static List<String> patchPhotonLodWaterRaytracer(List<String> lines) {
        for (String line : lines) {
            if (line.contains("VOXY_TRANSLUCENT_AFTER_DEFERRED")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 12);
        boolean defines = false, hand = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!defines && trimmed.equals("#if defined SSRT_LOD")) {
                result.add("#if defined VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("#define SSRT_DEPTH_SAMPLER vxDepthTexOpaque");
                result.add("#define SSRT_PROJECTION_MATRIX vxProj");
                result.add("#define SSRT_PROJECTION_MATRIX_INVERSE vxProjInv");
                result.add(line.replace("#if", "#elif"));
                defines = true;
                continue;
            }
            if (!hand && trimmed.equals("return hand_depth < final_depth && final_depth < 1.0;")) {
                String indent = line.substring(0, line.indexOf('r'));
                result.add("#ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add(indent + "return 0.0 < final_depth && final_depth < 1.0;");
                result.add("#else");
                result.add(line);
                result.add("#endif");
                hand = true;
                continue;
            }
            result.add(line);
        }
        return defines && hand ? result : lines;
    }

    //Photon's border fog ends the land at the vanilla render distance unless LOD_MOD_ACTIVE (VOXY or
    // DISTANT_HORIZONS) ends it at the LODs' instead, so under Vitrail the LODs were all border fog.
    // VOXY_LOD takes the LOD branch with Voxy's render distance, the pack's own LOD programs (which
    // have LOD_MOD_ACTIVE) keep theirs
    private static List<String> patchPhotonBorderFog(List<String> lines) {
        for (String line : lines) {
            if (line.contains("VOXY_LOD")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        int ifndef = -1, lodLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (ifndef < 0 && trimmed.equals("#ifndef LOD_MOD_ACTIVE")) {
                ifndef = i;
            } else if (ifndef >= 0 && trimmed.equals("float fog = length(scene_pos.xz) / float(lod_render_distance);")) {
                lodLine = i;
                break;
            }
        }
        if (ifndef < 0 || lodLine < 0) {
            return lines;
        }
        List<String> result = new ArrayList<>(lines.size() + 6);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == ifndef) {
                result.add(line.replace("#ifndef LOD_MOD_ACTIVE", "#if !defined LOD_MOD_ACTIVE && !defined VOXY_LOD"));
            } else if (i == lodLine) {
                String indent = line.substring(0, line.indexOf('f'));
                result.add("#ifdef LOD_MOD_ACTIVE");
                result.add(line);
                result.add("#else");
                result.add(indent + "float fog = length(scene_pos.xz) / float(VOXY_RENDER_DISTANCE);");
                result.add("#endif");
            } else {
                result.add(line);
            }
        }
        return result;
    }

    //Real water waves up and down while LOD water is flat, so where the two meet at the render distance
    // the edge of the real water follows the wave. Complementary already eases its waves out towards
    // the render distance for Voxy (DoWave_Water), behind #ifdef VOXY, which Vitrail does not define
    private static List<String> patchWavingBlocksGlsl(List<String> lines) {
        for (String line : lines) {
            if (line.contains("defined VOXY || defined VOXY_LOD")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.trim().equals("#ifdef VOXY")) {
                line = line.replace("#ifdef VOXY", "#if defined VOXY || defined VOXY_LOD");
            } else if (line.trim().equals("wave += 0.02 * renderDisEdge;")) {
                //The pack also lifts the real water 0.02 at the edge, which under Iris (where real water
                // never sees the LODs) only tucks the LOD water under it. Here the LOD water at the
                // border is pinned to the real water's own height, and the lift left a thin band where
                // real water stood over LOD water: two layers of water, a line along the border
                result.add("        #ifdef VOXY");
                result.add(line);
                result.add("        #endif");
                continue;
            }
            result.add(line);
        }
        return result;
    }

    //LODs are not in the shadow map, so the pack lights them from the sky light alone, and under water
    // (sky light drops a level per block of it) that is far darker than the water-tinted sunlight the
    // shadow map gives real terrain there. Complementary eases the real terrain's tinted light towards
    // the sky light estimate over the outer part of the render distance (GetShadow), so the two meet,
    // behind #if defined DISTANT_HORIZONS || defined VOXY, which never holds under Vitrail
    private static List<String> patchShadowSamplingGlsl(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.trim().equals("#if defined DISTANT_HORIZONS || defined VOXY")) {
                line = line.replace("#if defined DISTANT_HORIZONS || defined VOXY",
                        "#if defined DISTANT_HORIZONS || defined VOXY || defined VOXY_LOD");
            }
            result.add(line);
        }
        return result;
    }

    //The world outline (drawn in deferred1, before any water) lights up every sharp step in depth, and
    // at the border with the LODs there is one: the LODs' depth (their water surface included) beside
    // the real chunks' seabed, so the border was traced with a bright line through the water.
    // Complementary fades the outline out towards the render distance for exactly this, behind
    // #if defined DISTANT_HORIZONS || defined VOXY, which never holds under Vitrail
    private static List<String> patchWorldOutlineGlsl(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.trim().equals("#if defined DISTANT_HORIZONS || defined VOXY")) {
                line = line.replace("#if defined DISTANT_HORIZONS || defined VOXY",
                        "#if defined DISTANT_HORIZONS || defined VOXY || defined VOXY_LOD");
            }
            result.add(line);
        }
        return result;
    }

        //Real water reads its floor from the depth taken before translucents (depthtex1), and under Vitrail
    // that depth has the LODs in it, their water surface included. Along the border with the LODs the
    // real water ends a hair above the LOD water (the pack lifts it 0.02 there), so a thin band of real
    // water finds the LOD water surface as its floor: the shallowest water there is, which the pack
    // draws as solid shoreline foam and as nearly clear water, a bright line the length of the border.
    // Under Iris the LODs are never in that depth (excludeLodsFromVanillaDepth) and real water simply
    // finds nothing there, so a floor sample that is no real floor is dropped the same way: one less
    // than a tenth of a block under the surface (real terrain cannot be: its tops are whole blocks,
    // the water stands at 8/9) out at the edge of the real chunks
    private static List<String> patchWaterGlsl(List<String> lines) {
        for (String line : lines) {
            if (line.contains("voxyFloorPos")) {
                return lines;//already patched (Vitrail hands cached lines through again)
            }
        }
        List<String> result = new ArrayList<>(lines.size() + 12);
        boolean patched = false;
        for (String line : lines) {
            //The waves' parallax, which shifts the pattern with the viewing angle and is most of how
            // real water looks at a low angle; the LOD water program skipped it
            if (line.trim().equals("#if !defined LOW_QUALITY_WATER_MATERIAL && defined GBUFFERS_WATER")) {
                line = line.replace("#if !defined LOW_QUALITY_WATER_MATERIAL && defined GBUFFERS_WATER",
                        "#if !defined LOW_QUALITY_WATER_MATERIAL && (defined GBUFFERS_WATER || defined VOXY_TRANSLUCENT)");
            }
            if (!patched && line.trim().equals("float lViewPosT = length(viewPosT);")) {
                result.add("            #if defined GBUFFERS_WATER && defined VOXY_LOD && !defined VOXY");
                result.add("                {");
                result.add("                    vec3 voxyFloorPos = mat3(gbufferModelViewInverse) * viewPosT + gbufferModelViewInverse[3].xyz;");
                result.add("                    if (voxyFloorPos.y > playerPos.y - 0.1 && length(voxyFloorPos.xz) > far * 0.85) {");
                result.add("                        viewPosT = ScreenToView(vec3(screenPosT.xy, 1.0));");
                result.add("                    }");
                result.add("                }");
                result.add("            #endif");
                patched = true;
            }
            result.add(line);
        }
        return result;
    }

        //The pack's own Voxy LOD programs (read by VitrailVoxyPack, never by Vitrail) are written for a
    // build where Iris defines VOXY, which also decides which of their includes are taken. Defined
    // here, at the top of the file itself, so Vitrail's include expander sees it as well as the compiler
    private static List<String> defineVoxy(List<String> lines, boolean translucent) {
        //Vitrail keeps a file's lines once read and hands those back, through this hook again, so every
        // change here has to leave lines it already made alone
        if (lines.contains("#define VOXY")) {
            return lines;
        }
        if (translucent && isPhotonLodWater(lines)) {
            lines = patchPhotonLodWater(lines);
        }
        List<String> result = new ArrayList<>(lines.size() + 12);
        result.add("#ifndef VOXY");
        result.add("#define VOXY");
        result.add("#endif");
        for (String line : lines) {
            //Drawn where the real water is, after the deferred stage, the LOD water is no longer fogged
            // by it, so it fogs itself the way gbuffers_water does
            if (translucent && line.trim().equals("gbufferData0 = color;")) {
                result.add("    #ifdef VOXY_TRANSLUCENT_AFTER_DEFERRED");
                result.add("        float voxySkyFade = 0.0;");
                result.add("        float voxyPrevAlpha = color.a;");
                result.add("        color.a = 1.0;");
                result.add("        DoFog(color, voxySkyFade, lViewPos, playerPos, VdotU, VdotS, dither, false, 0.0);");
                result.add("        color.a = voxyPrevAlpha * (1.0 - voxySkyFade);");
                result.add("    #endif");
            }
            //The water's parallax (enabled for LOD water in water.glsl) reads the view vector in the
            // surface's tangent space, which is what real water's vertex stage hands it; the LOD program
            // only ever filled it with a placeholder
            if (line.trim().equals("viewVector = vec3(playerPos.x, playerPos.z, 0);")) {
                line = line.replace("viewVector = vec3(playerPos.x, playerPos.z, 0);", "viewVector = tbnMatrix * viewPos;");
            }
            //Complementary keeps colortex4 from frame to frame, the normal (and, from its deferred stage on,
            // the fresnel) of each pixel for its reflections: its real terrain writes it, its deferred stage
            // reads it back and writes it again. Under Iris the LODs never reach those stages, but under
            // Vitrail they stand in the game's depth and do, and with nothing of theirs written there they
            // took whatever normal was last left at that spot on screen, again every frame. A faint copy of
            // old terrain stood over the LODs and smeared about as the camera moved. The LOD terrain writes
            // its normal there as the real terrain does (a third draw buffer, see the voxy.json patch)
            if (line.trim().equals("layout(location = 1) out vec4 gbufferData6;")) {
                result.add(line);
                result.add("layout(location = 2) out vec4 gbufferData4;");
                continue;
            }
            if (line.trim().equals("gbufferData6 = vec4(smoothnessD, materialMask, skyLightFactor, 1.0);")) {
                result.add(line);
                result.add("    #if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0");
                result.add("    gbufferData4 = vec4(mat3(gbufferModelViewInverse) * normalM, 1.0);");
                result.add("    #else");
                result.add("    gbufferData4 = vec4(0.0);");
                result.add("    #endif");
                continue;
            }
            //Photon: a block its block.properties does not list has no id, which Vitrail (like Iris)
            // hands over as -1. As an unsigned number less 10000 that is still huge, so every such block
            // (stone, dirt, most of the land) became material 255, a hardcoded light source. The pack's
            // real terrain clamps the same subtraction as a signed number, which gives them material 0
            if (line.trim().equals("uint material_mask = max(parameters.customId - 10000u, 0u);")) {
                line = line.replace("max(parameters.customId - 10000u, 0u)", "uint(max(int(parameters.customId) - 10000, 0))");
            }
            result.add(line);
        }
        return result;
    }

    private static List<String> patchCommonGlsl(List<String> lines) {
        boolean alreadyPatched = false;
        for (String line : lines) {
            if (line.contains("VOXY_LOD")) {
                alreadyPatched = true;
                break;
            }
        }
        if (alreadyPatched) {
            return lines;
        }

        float rd = getVoxyRenderDistanceBlocks();
        List<String> result = new ArrayList<>(lines.size() + 8);
        result.add("#ifndef VOXY_LOD");
        result.add("#define VOXY_LOD");
        result.add(String.format(Locale.ROOT, "#define VOXY_RENDER_DISTANCE %.1f", rd));
        result.add("#endif");

        for (String line : lines) {
            if (line.trim().equals("float renderDistance = far;")) {
                result.add("        #ifdef VOXY_LOD");
                result.add("            float renderDistance = float(VOXY_RENDER_DISTANCE);");
                result.add("        #else");
                result.add("            float renderDistance = far;");
                result.add("        #endif");
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private static List<String> patchMainFogGlsl(List<String> lines) {
        boolean netherFogPatched = false;//(Vitrail hands cached lines through again)
        for (String line : lines) {
            netherFogPatched |= line.contains("lPos / float(VOXY_RENDER_DISTANCE)");
        }
        List<String> result = new ArrayList<>(lines.size() + 8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String l = line;
            //The Nether's border fog is whole at the Nether view limit (256 blocks by default), and for half
            // its weight at 256 whatever the limit, so every LOD lay in full fog. It also reads the LODs'
            // distance off the game's depth, which stops at the game's far plane, so no limit short of
            // limitless let them through. With the LODs out there it reaches their edge instead, as BSL's
            // Nether fog does for Voxy (the limit still bounds the Nether storm and the bloom fog)
            if (!netherFogPatched && l.trim().equals("float farM = min(renderDistance, NETHER_VIEW_LIMIT); // consistency9023HFUE85JG")
                    && i + 2 < lines.size()
                    && lines.get(i + 1).trim().equals("float fog = lPos / farM;")
                    && lines.get(i + 2).trim().equals("fog = mix(fog, min1(lPos / min(farM, 256.0)), 0.5);")) {
                String indent = l.substring(0, l.indexOf('f'));
                result.add("            #ifdef VOXY_LOD");
                result.add(indent + "float fog = lPos / float(VOXY_RENDER_DISTANCE);");
                result.add("            #else");
                result.add(l);
                result.add(lines.get(i + 1));
                result.add(lines.get(i + 2));
                result.add("            #endif");
                i += 2;
                continue;
            }
            if (l.contains("#if !defined DISTANT_HORIZONS && !defined VOXY") && !l.contains("VOXY_LOD")) {
                l = l.replace("#if !defined DISTANT_HORIZONS && !defined VOXY",
                        "#if !defined DISTANT_HORIZONS && !defined VOXY && !defined VOXY_LOD");
            }
            if (l.contains("#if defined DISTANT_HORIZONS || defined VOXY") && !l.contains("VOXY_LOD")) {
                l = l.replace("#if defined DISTANT_HORIZONS || defined VOXY",
                        "#if defined DISTANT_HORIZONS || defined VOXY || defined VOXY_LOD");
            }
            if (l.startsWith("    #ifdef ATMOSPHERIC_FOG")) {
                // When border fog is disabled in shader settings, disable atmospheric fog in DoFog too so there is NO fog on either chunks or LODs
                l = "    #if defined ATMOSPHERIC_FOG && defined BORDER_FOG";
            }
            result.add(l);
        }
        return result;
    }

    private static List<String> patchReflectionsGlsl(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size() + 2);
        for (String line : lines) {
            String l = line;
            if (l.contains("#ifndef DISTANT_HORIZONS")) {
                l = l.replace("#ifndef DISTANT_HORIZONS", "#if !defined DISTANT_HORIZONS && !defined VOXY_LOD");
            }
            //LOD water reflects along the full wave normal where real water softens it to 80% first,
            // which breaks its reflections up far more and shows as a border at low angles. The LOD
            // water program (VOXY_TRANSLUCENT) takes real water's road here
            //LOD water drawn before the deferred stage reads last frame's scene and reprojects into it;
            // drawn after it, the scene it reads is this frame's, as the real water's is
            if (l.trim().equals("#ifdef VOXY_PATCH")) {
                l = l.replace("#ifdef VOXY_PATCH", "#if defined VOXY_PATCH && !defined VOXY_TRANSLUCENT_AFTER_DEFERRED");
            }
            if (l.trim().equals("#if defined GBUFFERS_WATER && WATER_STYLE >= 2")) {
                l = l.replace("#if defined GBUFFERS_WATER && WATER_STYLE >= 2",
                        "#if (defined GBUFFERS_WATER || defined VOXY_TRANSLUCENT) && WATER_STYLE >= 2");
            }
            result.add(l);
        }
        return result;
    }

    public static GpuTextureView getColortex0() {
        if (!IS_LOADED) return null;
        if (!initialized) init();
        if (drawingPackMethod == null || activeField == null || targetsField == null || viewMethod == null || sideMain == null) {
            return null;
        }
        try {
            if (!(boolean) drawingPackMethod.invoke(null)) {
                return null;
            }
            Object activeChain = activeField.get(null);
            if (activeChain == null) {
                return null;
            }
            Object targets = targetsField.get(activeChain);
            if (targets == null) {
                return null;
            }
            return (GpuTextureView) viewMethod.invoke(targets, 0, sideMain);
        } catch (Throwable t) {
            return null;
        }
    }
}

