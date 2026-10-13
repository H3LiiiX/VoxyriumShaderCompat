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
        }
        return lines;
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
        List<String> result = new ArrayList<>(lines.size() + 8);
        for (String line : lines) {
            String l = line;
            if (l.contains("#if !defined DISTANT_HORIZONS && !defined VOXY")) {
                l = l.replace("#if !defined DISTANT_HORIZONS && !defined VOXY",
                        "#if !defined DISTANT_HORIZONS && !defined VOXY && !defined VOXY_LOD");
            }
            if (l.contains("#if defined DISTANT_HORIZONS || defined VOXY")) {
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

