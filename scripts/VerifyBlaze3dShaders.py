"""Compile production Blaze3D shaders and check the Mac Vulkan discard workaround.

Uses shaderc's C library without opening a graphics device or a Minecraft world.
Pass --library with an installed libshaderc_shared.so, libshaderc.dylib or shaderc_shared.dll.
"""

import argparse
import ctypes
from pathlib import Path
import re
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--require-explicit-locations", action="store_true",
                        help="Match Minecraft 26.3: reject undeclared shader input/output locations.")
    parser.add_argument("--resources", type=Path,
                        help="Processed resource directory for a selected Minecraft build.")
    args = parser.parse_args()
    library = ctypes.CDLL(str(args.library.resolve()))
    pointer = ctypes.c_void_p

    def function(name, result, arguments):
        func = getattr(library, "shaderc_" + name)
        func.restype, func.argtypes = result, arguments
        return func

    compiler = function("compiler_initialize", pointer, [])()
    options = function("compile_options_initialize", pointer, [])()
    if not compiler or not options:
        raise RuntimeError("Unable to initialize shaderc")
    target = function("compile_options_set_target_env", None, [pointer, ctypes.c_int, ctypes.c_uint])
    compile_shader = function("compile_into_spv", pointer,
                              [pointer, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int,
                               ctypes.c_char_p, ctypes.c_char_p, pointer])
    status = function("result_get_compilation_status", ctypes.c_int, [pointer])
    error = function("result_get_error_message", ctypes.c_char_p, [pointer])
    size = function("result_get_length", ctypes.c_size_t, [pointer])
    data = function("result_get_bytes", pointer, [pointer])
    release = function("result_release", None, [pointer])
    resources = args.resources or Path(__file__).resolve().parents[1] / "build/minecraft-26.2/resources/main"
    root = resources / "assets/voxy/shaders"
    if not root.is_dir():
        raise RuntimeError("Build processResources for the selected Minecraft version before checking shaders.")

    def expand(path):
        return re.sub(r"#(?:moj_import|include) <voxy:([^>]+)>",
                      lambda match: expand(root / "include" / match.group(1)), path.read_text())

    checked = 0
    try:
        function("compile_options_set_auto_bind_uniforms", None, [pointer, ctypes.c_bool])(options, True)
        function("compile_options_set_auto_map_locations", None, [pointer, ctypes.c_bool])(options, not args.require_explicit_locations)
        shaders = sorted(list((root / "core").glob("blaze3d_*.vsh"))
                         + list((root / "core").glob("blaze3d_*.fsh")))
        for environment, version, label in [(0, 4202496, "Vulkan 1.2"), (1, 450, "OpenGL 4.5")]:
            target(options, environment, version)
            for path in shaders:
                original = expand(path)
                variants = [False, True] if "VOXY_INIT_FRAGMENT" in original else [False]
                for masked in variants:
                    source = original
                    if masked:
                        source = source.replace("#version 330", "#version 330\n#define VOXY_SAMPLE_MASK_DISCARD", 1)
                    source = source.encode()
                    result = compile_shader(compiler, source, len(source), 0 if path.suffix == ".vsh" else 1,
                                            str(path).encode(), b"main", options)
                    if not result:
                        raise RuntimeError("No shaderc result for " + str(path))
                    try:
                        if status(result) != 0:
                            raise AssertionError(label + ": " + error(result).decode())
                        # Inspect actual compiled instructions, not just source macros.
                        binary = ctypes.string_at(data(result), size(result))
                        words = struct.unpack("<" + "I" * (len(binary) // 4), binary)
                        instructions = []
                        cursor = 5  # SPIR-V header
                        while cursor < len(words):
                            count, opcode = words[cursor] >> 16, words[cursor] & 65535
                            if not count or cursor + count > len(words):
                                raise AssertionError("Malformed SPIR-V")
                            instructions.append((opcode, words[cursor + 1:cursor + count]))
                            cursor += count
                        if "VOXY_INIT_FRAGMENT" in original:
                            kills = any(opcode in (252, 4416, 5380) for opcode, operands in instructions)
                            # OpDecorate with Decoration BuiltIn and BuiltIn SampleMask.
                            sample_mask = any(opcode == 71 and len(operands) == 3 and operands[1:] == (11, 20)
                                              for opcode, operands in instructions)
                            if masked and (kills or not sample_mask):
                                raise AssertionError("Safe shader still kills/demotes fragments or lacks SampleMask")
                            if not masked and (not kills or sample_mask):
                                raise AssertionError("Normal shader unexpectedly uses the sample-mask workaround")
                        checked += 1
                        print(f"PASS {label}: {path.name} ({'sample mask; no OpKill' if masked else 'normal'})")
                    finally:
                        release(result)
        print(f"PASS {checked} shader variants; production discard shaders checked in both environments.")
    finally:
        function("compile_options_release", None, [pointer])(options)
        function("compiler_release", None, [pointer])(compiler)


if __name__ == "__main__":
    main()
