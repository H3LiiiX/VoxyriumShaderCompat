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
    };
    public static final int FRAME_BLOCK_BYTES = 6 * 64 + 16 + 16;
    public static final int MAX_PACK_UNIFORM_BYTES = 16 * 1024;

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
    private final Blend translucentBlend;
    private final List<String> notes = new ArrayList<>();
    private final String glue;

    private VoxyPackPatch(String opaqueSource, String translucentSource, List<String> uniforms, Map<String, String> uniformTypes,
                          List<Sampler> samplers, int[] opaqueDrawBuffers, int[] translucentDrawBuffers, Blend translucentBlend) {
        this.opaqueSource = opaqueSource;
        this.translucentSource = translucentSource;
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.opaqueDrawBuffers = opaqueDrawBuffers;
        this.translucentDrawBuffers = translucentDrawBuffers;
        this.translucentBlend = translucentBlend;

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
     */
    public static VoxyPackPatch parse(String jsonText, String opaque, String translucent, Map<String, String> declaredTypes) {
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

        int[] opaqueBuffers = intArray(json, "opaqueDrawBuffers", new int[]{0});
        int[] translucentBuffers = intArray(json, "translucentDrawBuffers", new int[]{0});
        if (opaqueBuffers.length == 0 || opaqueBuffers[0] != 0) {
            throw new IllegalStateException("the first opaque draw buffer has to be colortex0");
        }
        if (opaqueBuffers.length > 4) {
            throw new IllegalStateException("at most 4 opaque draw buffers are supported, the pack writes " + opaqueBuffers.length);
        }
        if (translucentBuffers.length != 1 || translucentBuffers[0] != 0) {
            throw new IllegalStateException("translucent LODs can only be drawn into colortex0");
        }

        Blend blend = DEFAULT_BLEND;
        if (json.has("blending")) {
            blend = parseBlend(json.get("blending"));
        }

        return new VoxyPackPatch(patchSource(opaque), translucent == null ? null : patchSource(translucent), uniforms,
                declaredTypes, samplers, opaqueBuffers, translucentBuffers, blend);
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

    //Only buffer 0 exists for translucents here, so the answer is buffer 0's entry, else the -1
    // (every buffer) entry, else the default
    private static Blend parseBlend(JsonElement element) {
        if (element.isJsonPrimitive()) {
            return element.getAsString().equalsIgnoreCase("off") ? null : DEFAULT_BLEND;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement entry = object.has("0") ? object.get("0") : object.get("-1");
        if (entry == null) return DEFAULT_BLEND;
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
    private static String patchSource(String source) {
        source = VERSION.matcher(source).replaceAll("");
        return FRAG_COORD.matcher(source).replaceAll("voxy_FragCoord");
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

    /** The colortex indices the opaque pass writes after colortex0, in output order. */
    public int[] opaqueDataTargets() {
        int[] out = new int[this.opaqueDrawBuffers.length - 1];
        System.arraycopy(this.opaqueDrawBuffers, 1, out, 0, out.length);
        return out;
    }

    public Blend translucentBlend() {
        return this.translucentBlend;
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
}
