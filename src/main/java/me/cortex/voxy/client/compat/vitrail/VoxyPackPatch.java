package me.cortex.voxy.client.compat.vitrail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.*;

//A shaderpack's own Voxy LOD programs (voxy.json + voxy_opaque.glsl + voxy_translucent.glsl, the
// format upstream Voxy reads under Iris), prepared for Voxy's Vulkan terrain shaders.
//
//This half knows nothing about Vitrail or Minecraft: it is handed the pack's already expanded text
// and turns it into the GLSL appended to quads.frag. VitrailVoxyPack does the reading and serves the
// values every frame.
public final class VoxyPackPatch {
    //Bindings 0-10 belong to the terrain pipeline itself (quads.frag / quads3.vert)
    public static final int FRAME_UBO_BINDING = 11;
    public static final int PACK_UBO_BINDING = 12;
    public static final int SAMPLER_BINDING_BASE = 13;

    //The names Voxy answers itself rather than Vitrail: its own volume, and the size of the image
    // the LODs are rasterised into (which is not the pack's when Vitrail renders at a scale).
    // Fixed std140 layout, mirrored by writeFrameBlock.
    private static final String[][] FRAME_MEMBERS = {
            {"mat4", "vxModelView"}, {"mat4", "vxModelViewInv"}, {"mat4", "vxModelViewPrev"},
            {"mat4", "vxProj"}, {"mat4", "vxProjInv"}, {"mat4", "vxProjPrev"},
            {"vec4", "voxy_depthConv"},
            {"float", "viewWidth"}, {"float", "viewHeight"}, {"int", "vxRenderDistance"},
            //Appended after the rest so every member before keeps its offset
            {"mat4", "vxViewProj"}, {"mat4", "vxViewProjInv"}, {"mat4", "vxViewProjPrev"},
    };
    public static final int FRAME_BLOCK_BYTES = 6 * 64 + 16 + 16 + 3 * 64;
    public static final int MAX_PACK_UNIFORM_BYTES = 16 * 1024;
    //Colour attachments a pass may write besides Voxy's own (the SSAO / coverage flags after them)
    public static final int MAX_DRAW_BUFFERS = 6;

    public record Sampler(String name, String type, int binding) {}

    /** One translucent draw buffer's blend, as VK blend factors, or null when blending is off. */
    public record Blend(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {}

    public static final Blend DEFAULT_BLEND = new Blend(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);

    private final String opaqueSource;
    private final String translucentSource;
    private final List<String> uniforms;
    private final List<String> packUniforms;//the uniforms Vitrail answers, in block order
    private final Map<String, String> uniformTypes;
    private final List<Sampler> samplers;
    private final int[] opaqueDrawBuffers;
    private final int[] translucentDrawBuffers;
    private final Blend[] translucentBlends;
    private final List<String> notes = new ArrayList<>();
    private final String glue;

    private VoxyPackPatch(String opaqueSource, String translucentSource, List<String> uniforms, Map<String, String> uniformTypes,
                          List<Sampler> samplers, int[] opaqueDrawBuffers, int[] translucentDrawBuffers, Blend[] translucentBlends,
                          List<String> notes) {
        this.opaqueSource = opaqueSource;
        this.translucentSource = translucentSource;
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.opaqueDrawBuffers = opaqueDrawBuffers;
        this.translucentDrawBuffers = translucentDrawBuffers;
        this.translucentBlends = translucentBlends;
        this.notes.addAll(notes);

        Set<String> frameNames = new HashSet<>();
        for (var m : FRAME_MEMBERS) frameNames.add(m[1]);
        List<String> pack = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : uniforms) {
            if (frameNames.contains(name)) continue;
            String type = uniformTypes.get(name);
            if (type == null) {
                this.notes.add("no type is known for the uniform " + name + ", it is left undeclared");
                continue;
            }
            pack.add(name);
            types.put(name, type);
        }
        this.packUniforms = List.copyOf(pack);
        this.uniformTypes = types;
        this.glue = this.buildGlue();
    }

    //=================================== reading ===================================

    /**
     * @param jsonText      the pack's voxy.json, includes expanded and only its live lines kept
     * @param opaque        the pack's voxy_opaque program, expanded, with the defines it needs ahead of it
     * @param translucent   the same for voxy_translucent, or null when the pack has none
     * @param declaredTypes the GLSL type each uniform is declared with somewhere in the pack
     * @param ownDeclared   the {@code uniform type name;} lines the LOD programs themselves declare
     *                      outside any block (in code that is compiled), name to type
     */
    public static VoxyPackPatch parse(String jsonText, String opaque, String translucent, Map<String, String> declaredTypes,
                                      Map<String, String> ownDeclared) {
        JsonObject json = parseJson(jsonText);
        int version = json.has("version") ? json.get("version").getAsInt() : -1;
        if (version != 1) {
            throw new IllegalStateException("voxy.json declares version " + version + ", only version 1 is understood");
        }
        if (opaque == null) {
            throw new IllegalStateException("the pack has no voxy_opaque program");
        }

        List<String> uniforms = new ArrayList<>();
        if (json.has("uniforms")) {
            for (var e : json.getAsJsonArray("uniforms")) uniforms.add(e.getAsString());
        }

        List<Sampler> samplers = new ArrayList<>();
        int binding = SAMPLER_BINDING_BASE;
        if (json.has("samplers")) {
            JsonElement s = json.get("samplers");
            if (s.isJsonArray()) {
                for (var e : s.getAsJsonArray()) {
                    String name = e.getAsString();
                    samplers.add(new Sampler(name, name.startsWith("shadowtex") ? "sampler2DShadow" : "sampler2D", binding++));
                }
            } else {
                for (var e : s.getAsJsonObject().entrySet()) {
                    String type = e.getValue().isJsonNull()
                            ? (e.getKey().startsWith("shadowtex") ? "sampler2DShadow" : "sampler2D")
                            : e.getValue().getAsString();
                    samplers.add(new Sampler(e.getKey(), type, binding++));
                }
            }
        }

        //Under Iris a uniform the programs declare themselves is an ordinary uniform of the GL program
        // (Voxy only answers vxDepthTexOpaque / vxDepthTexTrans that way, which Photon reads without
        // listing). Here every one has to be in the glue, with a binding, so the declaration moves there:
        // a sampler as one more sampler, anything else into the pack block, answered by Vitrail by name
        List<String> notes = new ArrayList<>();
        Set<String> hoisted = new HashSet<>();
        if (ownDeclared != null) {
            Set<String> samplerNames = new HashSet<>();
            for (var s : samplers) samplerNames.add(s.name());
            for (var e : ownDeclared.entrySet()) {
                String name = e.getKey(), type = e.getValue();
                if (type.startsWith("sampler") || type.startsWith("usampler") || type.startsWith("isampler")) {
                    if (samplerNames.add(name)) {
                        samplers.add(new Sampler(name, type, binding++));
                        notes.add("the LOD programs declare the sampler " + name + " without listing it, it is served by name");
                    }
                } else if (!uniforms.contains(name)) {
                    uniforms.add(name);
                    declaredTypes = new HashMap<>(declaredTypes);
                    declaredTypes.put(name, type);
                    notes.add("the LOD programs declare the uniform " + name + " without listing it, it is answered by name");
                }
                hoisted.add(name);
            }
        }

        //A pack written against Iris may read OpenGL's fixed-function fog (Solas 3.8's modded fog reads
        // gl_Fog.start and gl_Fog.end), which Vulkan GLSL does not have. Vitrail answers it for the pack's
        // own programs as its uniform of_Fog, and so is it answered here (patchSource renames the reads)
        if ((GL_FOG.matcher(opaque).find() || (translucent != null && GL_FOG.matcher(translucent).find()))
                && !uniforms.contains(OF_FOG)) {
            uniforms.add(OF_FOG);
            declaredTypes = new HashMap<>(declaredTypes);
            declaredTypes.put(OF_FOG, OF_FOG_TYPE);
        }

        int[] opaqueBuffers = intArray(json, "opaqueDrawBuffers", new int[]{0});
        int[] translucentBuffers = intArray(json, "translucentDrawBuffers", new int[]{0});
        checkDrawBuffers("opaque", opaqueBuffers);
        checkDrawBuffers("translucent", translucentBuffers);

        Blend[] blends = new Blend[translucentBuffers.length];
        java.util.Arrays.fill(blends, DEFAULT_BLEND);
        if (json.has("blending")) {
            parseBlend(json.get("blending"), blends);
        }

        return new VoxyPackPatch(patchSource(opaque, hoisted), translucent == null ? null : patchSource(translucent, hoisted), uniforms,
                declaredTypes, samplers, opaqueBuffers, translucentBuffers, blends, notes);
    }

    private static void checkDrawBuffers(String pass, int[] buffers) {
        if (buffers.length == 0) {
            throw new IllegalStateException("the " + pass + " LODs draw into no buffer");
        }
        if (buffers.length > MAX_DRAW_BUFFERS) {
            throw new IllegalStateException("at most " + MAX_DRAW_BUFFERS + " " + pass + " draw buffers are supported, the pack writes "
                    + buffers.length);
        }
        Set<Integer> seen = new HashSet<>();
        for (int b : buffers) {
            if (b < 0 || !seen.add(b)) {
                throw new IllegalStateException("the " + pass + " draw buffers " + java.util.Arrays.toString(buffers) + " are not distinct colour targets");
            }
        }
    }

    private static final Pattern UNUSED_STRING = Pattern.compile("\"unusedString\"\\s*:\\s*\".*?\"\\s*,(?=\\s*\"[A-Za-z_]\\w*\"\\s*:)", Pattern.DOTALL);

    private static JsonObject parseJson(String text) {
        //A pack can carry a string that exists only to pull its option files through the
        // preprocessor (Complementary includes its whole common.glsl there). Expanded, that is code
        // full of quotes, so it is dropped before anything reads the json.
        text = UNUSED_STRING.matcher(text).replaceAll("");

        //The same two escapes IrisShaderPatch applies: a backslash in GLSL is not one in json, and a
        // quote inside a // comment of an embedded snippet would end the string
        text = text.replace("\\", "\\\\");
        StringBuilder builder = new StringBuilder(text.length());
        for (String line : text.split("\n")) {
            int idx = line.indexOf("//");
            if (idx != -1) {
                builder.append(line, 0, idx).append(line.substring(idx).replace("\"", "\\\""));
            } else {
                builder.append(line);
            }
            builder.append('\n');
        }

        var reader = new JsonReader(new StringReader(builder.toString()));
        reader.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(reader).getAsJsonObject();
    }

    private static int[] intArray(JsonObject json, String key, int[] fallback) {
        if (!json.has(key)) return fallback;
        JsonArray array = json.getAsJsonArray(key);
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }

    //Keyed as upstream Voxy keys it: by position in translucentDrawBuffers, -1 standing for every buffer
    // and applied first, so a buffer's own entry wins over it
    private static void parseBlend(JsonElement element, Blend[] out) {
        if (element.isJsonPrimitive()) {
            java.util.Arrays.fill(out, element.getAsString().equalsIgnoreCase("off") ? null : DEFAULT_BLEND);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("-1")) {
            java.util.Arrays.fill(out, blendEntry(object.get("-1")));
        }
        for (var e : object.entrySet()) {
            int buffer;
            try {
                buffer = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (buffer >= 0 && buffer < out.length) {
                out[buffer] = blendEntry(e.getValue());
            }
        }
    }

    private static Blend blendEntry(JsonElement entry) {
        List<String> parts;
        if (entry.isJsonArray()) {
            parts = new ArrayList<>();
            for (var e : entry.getAsJsonArray()) parts.add(e.getAsString());
        } else {
            String s = entry.getAsString().trim();
            if (s.equalsIgnoreCase("off")) return null;
            parts = List.of(s.split("\\s+"));
        }
        if (parts.size() < 4) return DEFAULT_BLEND;
        return new Blend(blendFactor(parts.get(0)), blendFactor(parts.get(1)), blendFactor(parts.get(2)), blendFactor(parts.get(3)));
    }

    private static int blendFactor(String name) {
        name = name.toUpperCase();
        if (name.startsWith("GL_")) name = name.substring(3);
        return switch (name) {
            case "ZERO" -> VK_BLEND_FACTOR_ZERO;
            case "ONE" -> VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default -> throw new IllegalStateException("Unknown blend factor " + name);
        };
    }

    private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\b.*$");

    //gl_FragCoord.z is the depth Voxy rasterised with (reversed Z on this backend) and the pack reads
    // the OpenGL window depth, so every read goes through voxy_FragCoord, which converts it the same
    // way the matrices handed over are converted. A #define cannot do it, the gl_ prefix is reserved.
    private static String patchSource(String source, Set<String> hoisted) {
        source = VERSION.matcher(source).replaceAll("");
        if (!hoisted.isEmpty()) {
            //The declarations parse() moved into the glue; blanked rather than removed so line numbers
            // in a compile error still point at the pack's own lines
            StringBuilder out = new StringBuilder(source.length());
            for (String line : source.split("\n", -1)) {
                Matcher m = OWN_UNIFORM_LINE.matcher(line);
                if (m.matches() && hoisted.contains(m.group(2))) {
                    line = "";
                } else if ((m = OWN_UNIFORM_LIST_LINE.matcher(line)).matches()) {
                    boolean all = true;
                    for (String name : m.group(2).split(",")) all &= hoisted.contains(name.trim());
                    if (all) line = "";
                }
                out.append(line).append('\n');
            }
            source = out.substring(0, out.length() - 1);
        }
        //Words Vulkan GLSL reserves for its separate sampler types and OpenGL leaves free, which a pack
        // may use as names (Photon's bicubic filter takes a "sampler2D sampler")
        source = VK_RESERVED.matcher(source).replaceAll("voxy_$1");
        source = GL_FOG.matcher(source).replaceAll(OF_FOG);
        return FRAG_COORD.matcher(source).replaceAll("voxy_FragCoord");
    }

    private static final Pattern VK_RESERVED = Pattern.compile("\\b(sampler|samplerShadow)\\b");

    private static final Pattern GL_FOG = Pattern.compile("\\bgl_Fog\\b");
    private static final String OF_FOG = "of_Fog";
    private static final String OF_FOG_TYPE = "OfFog";
    //Vitrail's own declaration of it (its Emitter), which its uniform block writes as std140
    private static final String OF_FOG_STRUCT = "struct OfFog { vec4 color; float density; float start; float end; float scale; };\n";

    //One plain declaration on its own line: no layout, no initialiser, no list
    private static final Pattern OWN_UNIFORM_LINE = Pattern.compile("\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;\\s*(?://.*)?");
    //The same with several names of one type (BSL's Nether colours: uniform float isValley, isCrimson, ...)
    private static final Pattern OWN_UNIFORM_LIST_LINE = Pattern.compile("\\s*uniform\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;\\s*(?://.*)?");

    /**
     * Collects the declaration a line of the LOD programs makes outside any block, if it is one that
     * parse() can move into the glue. Only lines that are compiled should be handed in.
     */
    public static void collectOwnUniform(String line, Map<String, String> into) {
        if (!line.contains("uniform")) return;
        Matcher m = OWN_UNIFORM_LINE.matcher(line);
        if (m.matches()) {
            into.putIfAbsent(m.group(2), m.group(1));
        } else if ((m = OWN_UNIFORM_LIST_LINE.matcher(line)).matches()) {
            for (String name : m.group(2).split(",")) {
                into.putIfAbsent(name.trim(), m.group(1));
            }
        }
    }

    //=================================== emitting ===================================

    private String buildGlue() {
        Set<String> listed = new HashSet<>(this.uniforms);
        StringBuilder b = new StringBuilder("\n\n//===== shaderpack LOD program glue =====\n");
        b.append("layout(binding = ").append(FRAME_UBO_BINDING).append(", std140) uniform VoxyPackFrame {\n");
        int unused = 0;
        for (var m : FRAME_MEMBERS) {
            //A name the pack did not ask for is not declared under it: the pack may use it for something of its own
            String name = m[1].startsWith("voxy_") || listed.contains(m[1]) ? m[1] : "voxy_unused" + (unused++);
            b.append("    ").append(m[0]).append(' ').append(name).append(";\n");
        }
        b.append("};\n");
        if (this.packUniforms.contains(OF_FOG)) {
            b.append(OF_FOG_STRUCT);
        }
        if (!this.packUniforms.isEmpty()) {
            b.append("layout(binding = ").append(PACK_UBO_BINDING).append(", std140) uniform VoxyPackUniforms {\n");
            for (String name : this.packUniforms) {
                b.append("    ").append(this.uniformTypes.get(name)).append(' ').append(name).append(";\n");
            }
            b.append("};\n");
        }
        for (var s : this.samplers) {
            b.append("layout(binding = ").append(s.binding()).append(") uniform ").append(s.type()).append(' ').append(s.name()).append(";\n");
        }
        b.append("#define voxy_FragCoord vec4(gl_FragCoord.xy, fma(voxy_depthConv.x, gl_FragCoord.z, voxy_depthConv.y), gl_FragCoord.w)\n\n");
        return b.toString();
    }

    /** What is appended to quads.frag (compiled with PATCHED_SHADER) for the opaque pass. */
    public String opaqueFragmentTail() {
        return this.glue + this.opaqueSource;
    }

    /** The same for the translucent pass, or null when the pack has no translucent program. */
    public String translucentFragmentTail() {
        return this.translucentSource == null ? null : this.glue + this.translucentSource;
    }

    public boolean hasTranslucent() {
        return this.translucentSource != null;
    }

    /** The colortex index each output of the opaque program goes to, in output (location) order. */
    public int[] opaqueDrawBuffers() {
        return this.opaqueDrawBuffers.clone();
    }

    /** The colortex index each output of the translucent program goes to, in output (location) order. */
    public int[] translucentDrawBuffers() {
        return this.translucentDrawBuffers.clone();
    }

    /** The blend of each translucent output, null where blending is off. */
    public Blend[] translucentBlends() {
        return this.translucentBlends.clone();
    }

    public List<Sampler> samplers() {
        return this.samplers;
    }

    /** The members of VoxyPackUniforms, in order, as (name, GLSL type). */
    public Map<String, String> packUniformTypes() {
        return this.uniformTypes;
    }

    public List<String> notes() {
        return this.notes;
    }

    //=================================== SPIR-V ===================================

    /**
     * The names of the variables a compiled module actually loads from. A sampler the pack declares
     * but never reads needs no descriptor, which matters because several of the names a pack lists
     * only exist under Iris (its shadow maps behind options, buffers only its Voxy deferred writes).
     */
    public static Set<String> loadedVariableNames(ByteBuffer spirv) {
        ByteBuffer buf = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int words = buf.remaining() / 4;
        int base = buf.position();
        Map<Integer, String> names = new HashMap<>();
        Set<Integer> loaded = new HashSet<>();
        int i = 5;
        while (i < words) {
            int word = buf.getInt(base + i * 4);
            int op = word & 0xFFFF;
            int count = word >>> 16;
            if (count == 0) break;
            if (op == 5 && count >= 3) {//OpName target "name"
                int target = buf.getInt(base + (i + 1) * 4);
                StringBuilder s = new StringBuilder();
                outer:
                for (int w = i + 2; w < i + count; w++) {
                    int v = buf.getInt(base + w * 4);
                    for (int k = 0; k < 4; k++) {
                        int c = (v >>> (k * 8)) & 0xFF;
                        if (c == 0) break outer;
                        s.append((char) c);
                    }
                }
                names.put(target, s.toString());
            } else if (op == 61 && count >= 4) {//OpLoad type result pointer
                loaded.add(buf.getInt(base + (i + 3) * 4));
            } else if (op == 57 && count >= 4) {//OpFunctionCall type result function args...
                //A sampler handed to a function as an argument (BSL's Raytrace(depthtex1, ...)) is loaded
                // inside it through the parameter, never through the variable itself
                for (int w = i + 4; w < i + count; w++) {
                    loaded.add(buf.getInt(base + w * 4));
                }
            }
            i += count;
        }
        Set<String> out = new HashSet<>();
        for (int id : loaded) {
            String name = names.get(id);
            if (name != null) out.add(name);
        }
        return out;
    }

    //=================================== uniform types ===================================

    private static final Pattern UNIFORM_DECL = Pattern.compile("\\buniform\\s+(\\w+)\\s+(\\w+)\\s*(?:=[^;]*)?;");

    /** Collects every {@code uniform type name;} a source line declares, first declaration winning. */
    public static void collectUniformTypes(String line, Map<String, String> into) {
        if (!line.contains("uniform")) return;
        Matcher m = UNIFORM_DECL.matcher(line);
        while (m.find()) {
            into.putIfAbsent(m.group(2), m.group(1));
        }
    }

    private static final Pattern UNIFORM_LIST = Pattern.compile("\\buniform\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;");

    /**
     * Collects the names of {@code uniform type a, b, c;} lists (BSL and Solas declare most of theirs
     * that way). Only fills names collectUniformTypes found no declaration for, so it is run after it
     * has seen every line.
     */
    public static void collectUniformListTypes(String line, Map<String, String> into) {
        if (!line.contains("uniform") || line.indexOf(',') < 0) return;
        Matcher m = UNIFORM_LIST.matcher(line);
        while (m.find()) {
            for (String name : m.group(2).split(",")) {
                into.putIfAbsent(name.trim(), m.group(1));
            }
        }
    }
}
