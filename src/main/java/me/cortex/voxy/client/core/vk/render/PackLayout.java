package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.compat.vitrail.VitrailVoxyPack;
import me.cortex.voxy.client.compat.vitrail.VoxyPackPatch;
import me.cortex.voxy.common.Logger;

import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

//Where the outputs of a shaderpack's LOD programs land: each output is rasterised into an image of
// Voxy's own (the LODs have their own depth, projection and stencil) and composited from there into the
// pack's colour target its voxy.json names, so the pack's later stages read LODs where they read real
// terrain.
//
//The output that goes to colortex0 is the lit colour: it takes Voxy's SSAO, the border fog and the
// fade, and its alpha is the coverage. Every other output is pack data (Photon writes no colour at all,
// only its packed gbuffer, which its deferred stage lights), so it is copied as written, at a precision
// that loses nothing of what the pack's own target holds.
public record PackLayout(
        int[] opaqueBuffers,      //the colortex each opaque output goes to
        int[] opaqueFormats,      //the format of the image each opaque output is rasterised into
        int colourOutput,         //the opaque output that is the lit colour (colortex0), or -1
        int[] translucentBuffers, //the colortex each translucent output is composited into, -1 = nowhere
        int[] translucentFormats,
        int[][] translucentBlends //per translucent output {srcColor, dstColor, srcAlpha, dstAlpha}, null = off
) {
    /** The format the lit colour is rasterised (and SSAO'd) in: HDR, with the alpha the coverage lives in. */
    public static final int COLOUR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    /**
     * Reads the layout of {@code patch} against the active pack's targets, or null (with the reason
     * logged) when one of them cannot be served.
     */
    public static PackLayout of(VoxyPackPatch patch) {
        int[] opaque = patch.opaqueDrawBuffers();
        int[] opaqueFormats = new int[opaque.length];
        int colour = -1;
        for (int i = 0; i < opaque.length; i++) {
            if (opaque[i] == 0) {
                colour = i;
                opaqueFormats[i] = COLOUR_FORMAT;
                continue;
            }
            int format = targetFormat(opaque[i]);
            if (format == VK_FORMAT_UNDEFINED) {
                Logger.error("The shaderpack's LOD programs draw into colortex" + opaque[i] + ", which the pack has no target for");
                return null;
            }
            opaqueFormats[i] = copyFormat(format);
            if (opaqueFormats[i] == VK_FORMAT_UNDEFINED) {
                Logger.error("The shaderpack's LOD programs draw into colortex" + opaque[i] + ", whose format (" + format + ") cannot be copied");
                return null;
            }
        }

        int[] translucent = patch.translucentDrawBuffers();
        var blends = patch.translucentBlends();
        int[] translucentDest = new int[translucent.length];
        int[] translucentFormats = new int[translucent.length];
        int[][] translucentBlends = new int[translucent.length][];
        boolean namesColortex0 = Arrays.stream(translucent).anyMatch(b -> b == 0);
        for (int i = 0; i < translucent.length; i++) {
            var blend = blends[i];
            translucentBlends[i] = blend == null ? null : new int[]{blend.srcColor(), blend.dstColor(), blend.srcAlpha(), blend.dstAlpha()};
            int dest = translucent[i];
            if (!VitrailVoxyPack.isWritten(dest) || targetFormat(dest) == VK_FORMAT_UNDEFINED) {
                //A buffer none of the pack's programs writes is one only its Voxy branches read: where
                // it merges the LOD water itself (BSL blends colortex16 over the scene in deferred1,
                // under #ifdef VOXY). Vitrail builds those branches out, so the LOD water goes where the
                // real water goes instead, colortex0, which is exactly what that merge amounted to
                if (i == 0 && !namesColortex0 && blend != null) {
                    Logger.info("Shaderpack LOD programs: colortex" + dest + " is only read by the pack's Voxy branches, "
                            + "the translucent LODs go into colortex0 with the real water instead");
                    dest = 0;
                } else {
                    Logger.info("Shaderpack LOD programs: colortex" + dest + " is only read by the pack's Voxy branches, "
                            + "translucent output " + i + " is dropped");
                    dest = -1;
                }
            }
            translucentDest[i] = dest;
            if (blend != null) {
                //Accumulated over transparent black and composited over the target afterwards
                translucentFormats[i] = COLOUR_FORMAT;
            } else {
                int format = dest < 0 ? VK_FORMAT_R16G16B16A16_SFLOAT : copyFormat(targetFormat(dest));
                if (format == VK_FORMAT_UNDEFINED) {
                    Logger.error("The shaderpack's translucent LOD programs draw into colortex" + dest + ", whose format cannot be copied");
                    return null;
                }
                translucentFormats[i] = format;
            }
        }
        return new PackLayout(opaque, opaqueFormats, colour, translucentDest, translucentFormats, translucentBlends);
    }

    private static int targetFormat(int index) {
        var view = VitrailVoxyPack.writeTarget(index);
        return view == null ? VK_FORMAT_UNDEFINED : VkFrameHost.vkFormat(view);
    }

    /**
     * The format a pack output is rasterised into before it is copied into a target of
     * {@code targetFormat}: half floats where they hold every value the target can (which keeps the
     * images Voxy always used for them), the target's own format where they do not, so a pack's packed
     * 16 bit data reaches its target bit for bit. Integer targets cannot go through a sampled copy.
     */
    static int copyFormat(int targetFormat) {
        return switch (targetFormat) {
            case VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM,
                 VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8_SINT,
                 VK_FORMAT_R8G8B8A8_UINT, VK_FORMAT_R8G8B8A8_SINT, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT,
                 VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16A16_UINT,
                 VK_FORMAT_R16G16B16A16_SINT, VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_UINT,
                 VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT,
                 VK_FORMAT_A2B10G10R10_UINT_PACK32, VK_FORMAT_UNDEFINED -> VK_FORMAT_UNDEFINED;
            default -> targetFormat;
        };
    }

    public int opaqueCount() {
        return this.opaqueBuffers.length;
    }

    public int translucentCount() {
        return this.translucentBuffers.length;
    }

    public boolean hasColour() {
        return this.colourOutput >= 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PackLayout l && Arrays.equals(this.opaqueBuffers, l.opaqueBuffers)
                && Arrays.equals(this.opaqueFormats, l.opaqueFormats) && this.colourOutput == l.colourOutput
                && Arrays.equals(this.translucentBuffers, l.translucentBuffers)
                && Arrays.equals(this.translucentFormats, l.translucentFormats)
                && Arrays.deepEquals(this.translucentBlends, l.translucentBlends);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.opaqueBuffers) * 31 + Arrays.hashCode(this.opaqueFormats) + this.colourOutput;
    }
}
