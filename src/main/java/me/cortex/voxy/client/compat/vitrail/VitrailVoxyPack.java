package me.cortex.voxy.client.compat.vitrail;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

//Serves the active Vitrail shaderpack's own Voxy LOD programs (voxy.json, voxy_opaque.glsl,
// voxy_translucent.glsl) to the Vulkan terrain renderer, so LODs are lit and coloured by the same
// code the pack lights its real terrain with instead of by the vanilla light map.
//
//Everything Vitrail-side is reached reflectively, the same way VitrailVkCompat does it: the pack is
// read with Vitrail's own include expander and settings (so options, dimension folders and the
// engine's defines all match what the pack's other programs were built with), the uniform block is
// filled by Vitrail's own UniformBlock against the chain's catalogue (the pack's custom uniforms
// included), and the samplers are the chain's own colour targets.
public final class VitrailVoxyPack {
    private static final boolean IS_LOADED = FabricLoader.getInstance().isModLoaded("vitrail");

    private static boolean reflected = false;
    private static boolean reflectionBroken = false;

    private static Method drawingPack;
    private static Field chainActive, chainValues, chainTargets, chainPackPath, chainChosen, chainProfile, chainWorld;
    private static Method openPack, openedSource, openedSettings;
    private static Constructor<?> expanderCtor;
    private static Method expand, unitLines, unitIsLive;
    private static Method sourceFile, sourceFiles, sourceReadLines;
    private static Method unitDefines;
    private static Method discoverDimensions, dimensionPlace;
    private static Method valuesCatalog, valuesWorld;
    private static Method catalogNatural;
    private static Constructor<?> uniformCtor, blockCtor;
    private static Method blockWrite, blockUnanswered, blockSize;
    private static Class<?> sinkClass;
    private static Method targetsView, targetsNoise, targetsWhite, targetsSchedule, targetsPackTexture;
    private static Method bindingView, bindingFilter, bindingRepeat;
    private static Object stageGbuffers;
    private static Method scheduleStep, scheduleStepAfterDeferred, scheduleSteps, boundRead, boundWrite, boundWrites;
    private static Method targetNameIndex;
    private static Field blockStateTable;

    //The chain the current answer was read for. Vitrail builds a new chain for every pack, setting
    // and dimension change, so its identity is the whole of the cache key.
    private static Object loadedFor;
    private static Loaded loaded;

    private record Loaded(Object chain, VoxyPackPatch patch, Object block, Object sink, long[] cursor,
                          Object gbufferStep, Object waterStep, java.util.Set<Integer> written, Object values, Object targets,
                          boolean complementaryFog) {}

    //Whether the LOD translucents are being drawn, which happens after the pack's deferred stage, where
    // the real water is: the targets they read and write are then on the halves the water's are
    private static boolean afterDeferred = false;

    public static void setAfterDeferred(boolean after) {
        afterDeferred = after;
    }

    private VitrailVoxyPack() {}

    //=================================== reflection ===================================

    private static boolean reflect() {
        if (reflected) return !reflectionBroken;
        reflected = true;
        try {
            Class<?> chain = Class.forName("dev.vitrail.render.PackChain");
            drawingPack = chain.getMethod("drawingPack");
            chainActive = field(chain, "active");
            chainValues = field(chain, "values");
            chainTargets = field(chain, "targets");
            chainPackPath = field(chain, "packPath");
            chainChosen = field(chain, "chosen");
            chainProfile = field(chain, "profile");
            chainWorld = field(chain, "world");

            Class<?> opened = Class.forName("dev.vitrail.pack.source.OpenedPack");
            openPack = opened.getMethod("open", Path.class, Map.class, String.class);
            openedSource = opened.getMethod("source");
            openedSettings = opened.getMethod("settings");

            Class<?> source = Class.forName("dev.vitrail.pack.source.ShaderPackSource");
            Class<?> settings = Class.forName("dev.vitrail.pack.option.SettingSet");
            Class<?> expander = Class.forName("dev.vitrail.pack.source.IncludeExpander");
            expanderCtor = expander.getConstructor(source, settings);
            expand = expander.getMethod("expand", Path.class);
            Class<?> unit = Class.forName("dev.vitrail.pack.source.IncludeExpander$ExpandedUnit");
            unitLines = unit.getMethod("lines");
            unitIsLive = unit.getMethod("isLive", int.class);
            sourceFile = source.getMethod("file", String.class);
            sourceFiles = source.getMethod("sourceFiles");
            sourceReadLines = source.getMethod("readLines", Path.class);
            unitDefines = settings.getMethod("unitDefines");

            Class<?> dims = Class.forName("dev.vitrail.pack.source.DimensionSet");
            discoverDimensions = dims.getMethod("discover", source);
            dimensionPlace = dims.getMethod("place", String.class);

            Class<?> values = Class.forName("dev.vitrail.render.PackValues");
            valuesCatalog = values.getMethod("catalog");
            valuesWorld = values.getMethod("world");
            Class<?> catalog = Class.forName("dev.vitrail.uniform.UniformCatalog");
            catalogNatural = catalog.getMethod("natural", String.class);

            Class<?> uniform = Class.forName("dev.vitrail.glsl.TranslatedUnit$Uniform");
            uniformCtor = uniform.getConstructor(String.class, String.class, String.class);
            Class<?> block = Class.forName("dev.vitrail.uniform.UniformBlock");
            blockCtor = block.getConstructor(List.class, catalog);
            Class<?> world = Class.forName("dev.vitrail.uniform.WorldState");
            sinkClass = Class.forName("dev.vitrail.uniform.UniformSink");
            blockWrite = block.getMethod("write", sinkClass, world);
            blockUnanswered = block.getMethod("unanswered");
            blockSize = block.getMethod("size");

            Class<?> targets = Class.forName("dev.vitrail.render.ColorTargets");
            Class<?> side = Class.forName("dev.vitrail.pack.target.TargetSchedule$Side");
            targetsView = method(targets, "view", int.class, side);
            targetsNoise = method(targets, "noise");
            targetsWhite = method(targets, "white");
            targetsSchedule = method(targets, "schedule");
            Class<?> textureStage = Class.forName("dev.vitrail.pack.model.TextureStage");
            targetsPackTexture = method(targets, "packTexture", textureStage, String.class);
            for (Object constant : textureStage.getEnumConstants()) {
                if ("GBUFFERS".equals(constant.toString())) stageGbuffers = constant;
            }
            Class<?> packBinding = Class.forName("dev.vitrail.render.ColorTargets$PackBinding");
            bindingView = method(packBinding, "view");
            bindingFilter = method(packBinding, "filter");
            bindingRepeat = method(packBinding, "repeat");
            Class<?> schedule = Class.forName("dev.vitrail.pack.target.TargetSchedule");
            scheduleStep = schedule.getMethod("step", String.class);
            scheduleSteps = schedule.getMethod("steps");
            scheduleStepAfterDeferred = schedule.getMethod("stepAfterDeferred", String.class);
            Class<?> bound = Class.forName("dev.vitrail.pack.target.TargetSchedule$Bound");
            boundRead = bound.getMethod("read", int.class);
            boundWrite = bound.getMethod("write", int.class);
            boundWrites = bound.getMethod("writes");
            targetNameIndex = Class.forName("dev.vitrail.pack.model.TargetName").getMethod("index", String.class);
            blockStateTable = field(Class.forName("dev.vitrail.render.BlockStateIds"), "table");
            for (Object constant : side.getEnumConstants()) {
                if ("MAIN".equals(constant.toString())) sideMain = constant;
            }
            return true;
        } catch (Throwable t) {
            reflectionBroken = true;
            Logger.warn("Vitrail's shaderpack LOD programs cannot be used with this version of Vitrail, LODs keep vanilla lighting", t);
            return false;
        }
    }

    private static Object sideMain;

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        Method m = owner.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    /**
     * The pack's block ids (its block.properties, as Vitrail read it), which is what the LOD programs
     * read as {@code mat}: the same numbers Iris hands Voxy for its model custom ids. Vitrail replaces
     * the table, never edits it, whenever a pack is read, so its identity says when the ids changed.
     * Null without Vitrail.
     */
    @SuppressWarnings("unchecked")
    public static it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState> blockStateIds() {
        if (!IS_LOADED || !reflect()) return null;
        try {
            return (it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState>) blockStateTable.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    //=================================== loading ===================================

    /**
     * The active pack's LOD programs, or null when no pack is drawing, the pack ships none, or they
     * could not be read. Render thread only; reads the pack again only when Vitrail's chain changes.
     */
    public static VoxyPackPatch current() {
        if (!IS_LOADED || !reflect()) return null;
        Object chain;
        try {
            if (!(boolean) drawingPack.invoke(null)) return null;
            chain = chainActive.get(null);
        } catch (Throwable t) {
            return null;
        }
        if (chain == null) return null;
        if (chain != loadedFor) {
            loadedFor = chain;
            loaded = null;
            try {
                loaded = load(chain);
            } catch (Throwable t) {
                Logger.error("Failed to load the shaderpack's Voxy LOD programs, LODs keep vanilla lighting", t);
            }
        }
        return loaded == null ? null : loaded.patch;
    }

    /** Drops a patch whose shaders could not be built, so LODs fall back to vanilla lighting. */
    public static void markFailed(VoxyPackPatch patch, Throwable cause) {
        if (loaded != null && loaded.patch == patch) {
            loaded = null;
            Logger.error("The shaderpack's Voxy LOD programs failed to build, LODs keep vanilla lighting", cause);
        }
    }

    private static Loaded load(Object chain) throws Throwable {
        Path packPath = (Path) chainPackPath.get(chain);
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen = (Map<String, Object>) chainChosen.get(chain);
        String profile = (String) chainProfile.get(chain);
        String world = (String) chainWorld.get(chain);
        Object values = chainValues.get(chain);
        Object targets = chainTargets.get(chain);

        //An opening of our own: the one the chain was loaded from belongs to Vitrail's loader alone
        try (AutoCloseable opened = (AutoCloseable) openPack.invoke(null, packPath, chosen, profile)) {
            Object source = openedSource.invoke(opened);
            Object settings = openedSettings.invoke(opened);
            String place = (String) dimensionPlace.invoke(discoverDimensions.invoke(null, source), world);
            String prefix = place == null || place.isEmpty() ? "" : place + "/";

            Path json = find(source, prefix + "voxy.json", "voxy.json");
            if (json == null) {
                Logger.info("The shaderpack has no voxy.json, LODs keep vanilla lighting");
                return null;
            }
            Path opaque = find(source, prefix + "voxy_opaque.glsl", "voxy_opaque.glsl");
            Path translucent = find(source, prefix + "voxy_translucent.glsl", "voxy_translucent.glsl");

            StringBuilder header = new StringBuilder();
            @SuppressWarnings("unchecked")
            Map<String, String> defines = (Map<String, String>) unitDefines.invoke(settings);
            for (var e : defines.entrySet()) {
                header.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            }

            String jsonText = liveText(expand(source, settings, json));
            Object opaqueUnit = opaque == null ? null : expand(source, settings, opaque);
            Object translucentUnit = translucent == null ? null : expand(source, settings, translucent);
            String opaqueText = opaqueUnit == null ? null : header + String.join("\n", lines(opaqueUnit));
            String translucentText = translucentUnit == null ? null : header + String.join("\n", lines(translucentUnit));
            //What the programs declare for themselves, in the code that is compiled
            Map<String, String> ownDeclared = new java.util.LinkedHashMap<>();
            collectOwnDeclared(opaqueUnit, ownDeclared);
            collectOwnDeclared(translucentUnit, ownDeclared);

            //The pack declares a uniform's type where its other programs read it (lib/uniforms.glsl in
            // Complementary); the engine's own shape for the name stands in where it declares none
            Map<String, String> types = new HashMap<>();
            //Complementary, whose border fog Voxy draws over the LODs for it (its fog code is the one
            // VitrailVkCompat patches); told apart by the file that fog lives in
            boolean complementaryFog = false;
            @SuppressWarnings("unchecked")
            List<Path> files = (List<Path>) sourceFiles.invoke(source);
            for (Path file : files) {
                if (file.toString().replace('\\', '/').endsWith("lib/atmospherics/fog/mainFog.glsl")) {
                    complementaryFog = true;
                }
                @SuppressWarnings("unchecked")
                List<String> fileLines = (List<String>) sourceReadLines.invoke(source, file);
                for (String line : fileLines) {
                    VoxyPackPatch.collectUniformTypes(line, types);
                }
            }
            Object catalog = valuesCatalog.invoke(values);
            Map<String, String> withEngine = new HashMap<>(types);
            Map<String, String> listTypes = null;
            for (String name : jsonUniformNames(jsonText)) {
                if (!withEngine.containsKey(name)) {
                    Object shape = catalogNatural.invoke(catalog, name);
                    String glsl = shape == null ? null : glslType(shape.toString());
                    if (glsl == null) {
                        //Last, a declaration in a list (uniform float a, b;), which is how BSL and Solas
                        // declare most of theirs
                        if (listTypes == null) {
                            listTypes = new HashMap<>();
                            for (Path file : files) {
                                @SuppressWarnings("unchecked")
                                List<String> fileLines = (List<String>) sourceReadLines.invoke(source, file);
                                for (String line : fileLines) {
                                    VoxyPackPatch.collectUniformListTypes(line, listTypes);
                                }
                            }
                        }
                        glsl = listTypes.get(name);
                    }
                    if (glsl != null) withEngine.put(name, glsl);
                }
            }

            VoxyPackPatch patch = VoxyPackPatch.parse(jsonText, opaqueText, translucentText, withEngine, ownDeclared);

            List<Object> members = new ArrayList<>();
            for (var e : patch.packUniformTypes().entrySet()) {
                members.add(uniformCtor.newInstance(e.getKey(), e.getValue(), e.getValue() + " " + e.getKey()));
            }
            Object block = blockCtor.newInstance(members, catalog);
            int size = (int) blockSize.invoke(block);
            if (size > VoxyPackPatch.MAX_PACK_UNIFORM_BYTES) {
                throw new IllegalStateException("the LOD programs' uniforms take " + size + " bytes, more than "
                        + VoxyPackPatch.MAX_PACK_UNIFORM_BYTES);
            }
            @SuppressWarnings("unchecked")
            List<String> unanswered = (List<String>) blockUnanswered.invoke(block);

            long[] cursor = new long[2];//address, offset
            Object sink = Proxy.newProxyInstance(VitrailVoxyPack.class.getClassLoader(), new Class<?>[]{sinkClass},
                    (proxy, method, args) -> std140(proxy, method.getName(), args, cursor));

            Object step = gbufferStep(targets);

            for (String note : patch.notes()) {
                Logger.warn("Shaderpack LOD programs: " + note);
            }
            if (!unanswered.isEmpty()) {
                Logger.warn("Shaderpack LOD programs: Vitrail answers none of " + unanswered + ", they read as zero");
            }
            Logger.info("Loaded the shaderpack's Voxy LOD programs (" + (prefix.isEmpty() ? "root" : place) + ", "
                    + patch.packUniformTypes().size() + " uniforms, " + patch.samplers().size() + " samplers"
                    + (patch.hasTranslucent() ? ", with translucents)" : ")"));
            return new Loaded(chain, patch, block, sink, cursor, step, waterStep(targets), writtenTargets(targets), values, targets,
                    complementaryFog);
        }
    }

    private static void collectOwnDeclared(Object unit, Map<String, String> into) throws Throwable {
        if (unit == null) return;
        List<String> unitLines = lines(unit);
        for (int i = 0; i < unitLines.size(); i++) {
            if ((boolean) unitIsLive.invoke(unit, i)) {
                VoxyPackPatch.collectOwnUniform(unitLines.get(i), into);
            }
        }
    }

    /**
     * Whether a program of the active pack writes {@code colortex<index>}. A buffer none of them writes
     * is only read by the pack's Voxy branches, which Vitrail builds out. True when it cannot be told.
     */
    public static boolean isWritten(int index) {
        Loaded l = loaded;
        return l == null || l.written == null || l.written.contains(index);
    }

    /**
     * Whether the pack whose LOD programs are loaded is Complementary, whose border fog (its BORDER_FOG
     * setting) Voxy draws over the LODs for it. Null when no LOD programs are loaded.
     */
    public static Boolean loadedHasComplementaryFog() {
        Loaded l = loaded;
        return l == null ? null : l.complementaryFog;
    }

    private static Path find(Object source, String... candidates) throws Throwable {
        for (String candidate : candidates) {
            @SuppressWarnings("unchecked")
            Optional<Path> file = (Optional<Path>) sourceFile.invoke(source, candidate);
            if (file.isPresent()) return file.get();
        }
        return null;
    }

    private static Object expand(Object source, Object settings, Path entry) throws Throwable {
        return expand.invoke(expanderCtor.newInstance(source, settings), entry);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Object unit) throws Throwable {
        return (List<String>) unitLines.invoke(unit);
    }

    //voxy.json goes through the preprocessor like a program does (Complementary's has #include and
    // #if MC_VERSION in it), but nothing compiles it, so what the expander decided is live is kept
    // and every directive is dropped
    private static String liveText(Object unit) throws Throwable {
        List<String> lines = lines(unit);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (!(boolean) unitIsLive.invoke(unit, i)) continue;
            String line = lines.get(i);
            if (line.stripLeading().startsWith("#")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static List<String> jsonUniformNames(String jsonText) {
        List<String> out = new ArrayList<>();
        int at = jsonText.indexOf("\"uniforms\"");
        if (at < 0) return out;
        int open = jsonText.indexOf('[', at), close = jsonText.indexOf(']', open);
        if (open < 0 || close < 0) return out;
        var m = java.util.regex.Pattern.compile("\"(\\w+)\"").matcher(jsonText.substring(open, close));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String glslType(String shape) {
        return switch (shape) {
            case "FLOAT" -> "float";
            case "INT" -> "int";
            case "VEC2" -> "vec2";
            case "VEC3" -> "vec3";
            case "VEC4" -> "vec4";
            case "IVEC2" -> "ivec2";
            case "IVEC3" -> "ivec3";
            case "IVEC4" -> "ivec4";
            case "MAT3" -> "mat3";
            case "MAT4" -> "mat4";
            default -> null;
        };
    }

    private static Object gbufferStep(Object targets) {
        try {
            Object schedule = targetsSchedule.invoke(targets);
            for (String program : new String[]{"gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured"}) {
                Optional<?> step = (Optional<?>) scheduleStep.invoke(schedule, program);
                if (step.isPresent()) return step.get();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object waterStep(Object targets) {
        try {
            Object schedule = targetsSchedule.invoke(targets);
            for (String program : new String[]{"gbuffers_water", "gbuffers_textured_lit", "gbuffers_textured"}) {
                Optional<?> step = (Optional<?>) scheduleStepAfterDeferred.invoke(schedule, program);
                if (step.isPresent()) return step.get();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    //Every colour target some program of the chain writes, or null when that cannot be read
    private static java.util.Set<Integer> writtenTargets(Object targets) {
        try {
            java.util.Set<Integer> out = new java.util.HashSet<>();
            for (Object step : (List<?>) scheduleSteps.invoke(targetsSchedule.invoke(targets))) {
                for (Object index : (List<?>) boundWrites.invoke(step)) {
                    out.add((Integer) index);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    //=================================== per frame ===================================

    /**
     * Writes VoxyPackUniforms for this frame at {@code address} (std140, the order the glue declared).
     * Returns the number of bytes written.
     */
    public static int writeUniforms(long address) {
        Loaded l = loaded;
        if (l == null) return 0;
        try {
            l.cursor[0] = address;
            l.cursor[1] = 0;
            blockWrite.invoke(l.block, l.sink, valuesWorld.invoke(l.values));
            return (int) l.cursor[1];
        } catch (Throwable t) {
            return 0;
        }
    }

    /** What a sampler of the LOD programs reads this frame, and how. */
    public record Sampled(GpuTextureView view, boolean linear, boolean repeat) {}

    /** The image a sampler of the LOD programs reads this frame, or null when Vitrail serves none for it. */
    public static Sampled sampler(String name) {
        Loaded l = loaded;
        if (l == null) return null;
        try {
            //A texture the pack supplies itself for the gbuffers stage wins over the buffer of the same
            // name, as it does for the pack's own terrain: Complementary's water normals are
            // texture.gbuffers.gaux4=lib/textures/cloud-water.png, not colortex7
            Object binding = targetsPackTexture.invoke(l.targets, stageGbuffers, name);
            if (binding != null) {
                return new Sampled((GpuTextureView) bindingView.invoke(binding),
                        "LINEAR".equals(String.valueOf(bindingFilter.invoke(binding))),
                        (boolean) bindingRepeat.invoke(binding));
            }
            if ("noisetex".equals(name)) {
                //Repeating and filtered, Iris's choice: a pack indexes it well past one texel
                return new Sampled((GpuTextureView) targetsNoise.invoke(l.targets), true, true);
            }
            GpuTextureView view = samplerView(l, name);
            return view == null ? null : new Sampled(view, false, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static GpuTextureView samplerView(Loaded l, String name) {
        try {
            OptionalInt index = (OptionalInt) targetNameIndex.invoke(null, name);
            if (index.isPresent()) {
                int i = index.getAsInt();
                //The pack's other programs are built without VOXY under Vitrail, so the buffers only
                // its Voxy branches fill (Complementary's colortex18, LOD screen space shadows, and
                // colortex19, the scene copy LOD water reflects) may be allocated and still never
                // written. Read as never written, such a buffer is garbage, so it reads white instead,
                // except colortex19: colortex5 holds the same picture for the pack's own water (last
                // frame's, as colortex19 would)
                if (l.written != null && !l.written.contains(i)) {
                    return i == 19 && l.written.contains(5) ? target(l, 5, false) : null;
                }
                GpuTextureView view = target(l, i, false);
                if (view == null && i == 19) {
                    view = target(l, 5, false);
                }
                return view;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** The colour target the gbuffer stage writes {@code colortex<index>} into, or null. */
    public static GpuTextureView writeTarget(int index) {
        Loaded l = loaded;
        if (l == null) return null;
        try {
            return target(l, index, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method targetsCoverage;
    private static boolean coverageReflected;

    /**
     * Vitrail's coverage mask (R32F): the depth the pack's own geometry left at every pixel it drew.
     * Vitrail's scene seed paints the game's own picture into the terrain's first draw buffer wherever
     * the world's depth is in front of the mask, so a pixel the mask does not claim is painted over.
     * Null when Vitrail has none (or no longer has this method).
     */
    public static GpuTextureView coverage() {
        Loaded l = loaded;
        if (l == null) return null;
        try {
            if (!coverageReflected) {
                coverageReflected = true;
                targetsCoverage = method(Class.forName("dev.vitrail.render.ColorTargets"), "coverage");
            }
            return targetsCoverage == null ? null : (GpuTextureView) targetsCoverage.invoke(l.targets);
        } catch (Throwable t) {
            return null;
        }
    }

    public static GpuTextureView white() {
        Loaded l = loaded;
        if (l == null) return null;
        try {
            return (GpuTextureView) targetsWhite.invoke(l.targets);
        } catch (Throwable t) {
            return null;
        }
    }

    private static GpuTextureView target(Loaded l, int index, boolean write) throws Throwable {
        Object step = afterDeferred && l.waterStep != null ? l.waterStep : l.gbufferStep;
        Object side = step == null ? sideMain
                : (write ? boundWrite : boundRead).invoke(step, index);
        return (GpuTextureView) targetsView.invoke(l.targets, index, side);
    }

    //A std140 writer behind Vitrail's UniformSink. UniformBlock only aligns arrays itself; the base
    // alignment of every member is the sink's to apply, exactly as the GLSL compiler lays out the
    // block the glue declared.
    private static Object std140(Object proxy, String name, Object[] args, long[] cursor) {
        switch (name) {
            case "member" -> {}
            case "align" -> align(cursor, (int) args[0]);
            case "putFloat" -> {
                align(cursor, 4);
                MemoryUtil.memPutFloat(cursor[0] + cursor[1], (float) args[0]);
                cursor[1] += 4;
            }
            case "putInt" -> {
                align(cursor, 4);
                MemoryUtil.memPutInt(cursor[0] + cursor[1], (int) args[0]);
                cursor[1] += 4;
            }
            case "putVec2" -> floats(cursor, 8, args);
            case "putVec3" -> floats(cursor, 16, args);
            case "putVec4" -> floats(cursor, 16, args);
            case "putIVec2" -> ints(cursor, 8, args);
            case "putIVec3" -> ints(cursor, 16, args);
            case "putIVec4" -> ints(cursor, 16, args);
            case "putMat3" -> {
                align(cursor, 16);
                Matrix3fc m = (Matrix3fc) args[0];
                for (int c = 0; c < 3; c++) {
                    long at = cursor[0] + cursor[1] + c * 16L;
                    MemoryUtil.memPutFloat(at, m.get(c, 0));
                    MemoryUtil.memPutFloat(at + 4, m.get(c, 1));
                    MemoryUtil.memPutFloat(at + 8, m.get(c, 2));
                    MemoryUtil.memPutFloat(at + 12, 0);
                }
                cursor[1] += 48;
            }
            case "putMat4" -> {
                align(cursor, 16);
                ((Matrix4fc) args[0]).getToAddress(cursor[0] + cursor[1]);
                cursor[1] += 64;
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == args[0];
            }
            case "toString" -> {
                return "VoxyStd140Sink";
            }
            default -> {}
        }
        return proxy;
    }

    private static void align(long[] cursor, int alignment) {
        cursor[1] = (cursor[1] + alignment - 1) & -(long) alignment;
    }

    private static void floats(long[] cursor, int alignment, Object[] args) {
        align(cursor, alignment);
        for (Object a : args) {
            MemoryUtil.memPutFloat(cursor[0] + cursor[1], (float) a);
            cursor[1] += 4;
        }
    }

    private static void ints(long[] cursor, int alignment, Object[] args) {
        align(cursor, alignment);
        for (Object a : args) {
            MemoryUtil.memPutInt(cursor[0] + cursor[1], (int) a);
            cursor[1] += 4;
        }
    }
}
