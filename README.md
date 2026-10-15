# Voxyrium with support for Vitrail Shaders

A fork of Voxy with support for Vitrail Shaders and native OpenGL/Vulkan and experimental Blaze3D rendering. Select the renderer from Voxy's Rendering settings.

## Changes

* **Vitrail shader support**, tested on high presets with BSL, Complementary, Photon and Solas. The compatibility changes were done by Claude so it is likely not perfect and will contain bugs. However for most players who just stick to the presets there should not be any noticeable issues. Please report any issues you find.
* **Full Vulkan support**, based on [MCRcortex/voxy#614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.
* **Intel Mac support** in addition to Apple Silicon.
* Additional **MoltenVK-specific fixes**.
* Fixed gaps between blocks in **flowing water and flowing lava** in Voxy chunks.
* Added an alternative **Blaze3D-only renderer**, written from scratch without direct OpenGL or Vulkan calls.

## Blaze3D Renderer (Experimental)

The alternative renderer uses Minecraft's public **Blaze3D API** in 26.2 and 26.3. It should therefore run on any device capable of Minecraft's minimum graphics requirements, including **OpenGL 3.3** hardware.

Its goal is to reproduce Voxy's rendering and performance as closely as possible while remaining completely independent of the underlying graphics API.

There is, however, a fundamental performance ceiling: Blaze3D does not expose enough of the GPU to reproduce Voxy's native GPU-driven pipeline.

| GPU feature                       | Blaze3D 26.2 / 26.3 | Limitation                                                            |
| --------------------------------- | ------------ | --------------------------------------------------------------------- |
| Vertex/index/uniform/copy buffers | ✅            | Fully usable                                                          |
| Indirect command buffers          | ⚠️           | `drawIndexedIndirect(...)`, but one command per call                  |
| Multi-draw indirect               | ❌            | No `glMultiDraw*Indirect` / Vulkan equivalent                         |
| GPU-generated draw count          | ❌            | No `glMultiDraw*IndirectCount` / `vkCmdDraw*IndirectCount` equivalent |
| Compute shaders                   | ❌            | No compute pipelines, `dispatch` or `dispatchIndirect`                |
| SSBO / storage buffers            | ❌            | No public shader-storage buffer abstraction                           |
| Storage images                    | ❌            | No image load/store abstraction                                       |
| Explicit synchronization          | ❌            | No programmable compute/storage/indirect barriers                     |
| GPU-driven Hi-Z traversal         | ❌            | Hi-Z can be raster-generated, but cannot drive a compute traversal    |

Consequently, the Blaze3D renderer cannot reproduce several techniques available to Voxy's native **OpenGL 4.6** and **Vulkan** backends:

* GPU compute culling and traversal
* GPU-generated indirect draw lists
* Multi-draw indirect submission
* GPU-generated draw counts
* SSBO-based scene/visibility data
* Storage-image compute workloads
* Explicit synchronization between compute, transfer and indirect rendering stages

These limitations require more work to remain on the CPU and/or more individual draw submissions.

The Blaze3D backend should therefore be considered a **portable compatibility renderer**, not a replacement for the native backends. It aims to approach native Voxy performance and visual fidelity as closely as Blaze3D permits.

## Rendering Backends

| Backend     | Requirements                               | GPU-driven capabilities | Expected performance          |
| ----------- | ------------------------------------------ | ----------------------- | ----------------------------- |
| **OpenGL**  | Voxy native requirements                   | Full                    | Best                          |
| **Vulkan**  | Vulkan / MoltenVK                          | Full                    | Best                          |
| **Blaze3D** | Minecraft-compatible GPU, including GL 3.3 | Limited by Blaze3D      | Lower, compatibility-oriented |

## Compatibility Builds and Testing

Minecraft **26.2 and 26.3 share the `dev` branch**. The default target is 26.2;
select 26.3 without changing the source baseline or dependency properties:

```bash
./gradlew -Pminecraft_version=26.2 build
./gradlew -Pminecraft_version=26.3 build
```

Java 25 is required for both. Each target has its own output directory,
`build/minecraft-26.2/` or `build/minecraft-26.3/`, and a JAR named for its
Minecraft version. The compatibility script selects the same branch for both:

```bash
python3 scripts/Build-CompatibilityMatrix.py --action compile --versions 26.2 26.3
```

API differences in Java, shaders, mixin declarations and the access widener use
`//? if MC_26_3`, `//? else`, `//? endif` sections (`#?` in the access widener).
`MC_26_2` selects the older API. Gradle preprocesses these sections before
compiling and packaging; edit the shared files in `src/`, not generated files.
Dependencies for each target are declared in `gradle/minecraft-versions.gradle`.

The scripts in [`scripts/`](scripts/) make it easier to backport new game features continuously to the Minecraft **1.20.x and 1.21.x** branches. They build the selected branch/version combinations, prepare matching Fabric test profiles, and keep the runtime dependencies aligned so changes can be checked across the supported versions as they are developed.

### Getting started

Run the interactive menu from the repository root:

```text
Windows: scripts\Run-Scripts-Windows.bat
macOS:   scripts/Run-Scripts-macOS.command
Other:   python3 scripts/Run-Scripts.py
```

The menu launches the available capitalized Python scripts. Python 3 and Git are required; compilation also requires the appropriate JDKs (Java 17 for older 1.20.x targets, Java 21 for newer 1.20.x and 1.21.x targets, and Java 25 for 26.x). `InstallJdkVersions.py` can install the toolchains used by the compatibility builds. The build/profile scripts can also be run directly with Python; use `--help` to see their options.

### Main workflows

| Script | Purpose |
| --- | --- |
| `Build-CompatibilityMatrix.py` | Main workflow. Selects `compile` to build the chosen versions, `compile-online` to also prepare toolchains and refresh dependencies, `profiles` to create/update test profiles, `dependencies` to update their mods and shaders, or `jdks` to install toolchains. Without `--action`, it offers an interactive choice. |
| `CreateUpdateProfiles.py` | Creates or refreshes launcher profiles and their version-specific runtime setup; can also build and install artifacts as part of profile preparation. |
| `InstallJdkVersions.py` | Installs required Java toolchains for the compatibility builds. |
| `UpdateProfileDependencies.py` | Updates runtime mods and shader packs for existing test profiles. |
| `Find-Latest-Iris-Sodium.py` | Finds the newest mutually compatible Iris/Sodium pair for each 1.21.x release and writes the result to `latest-iris-sodium-1.21.txt`. This checks published metadata and manifests, not in-game behavior. |
| `Start-MinecraftOffline.py` | Launches one or more prepared `voxy-test-*` profiles without Minecraft account authentication. |
| `ToggleVoxyFromProfiles.py` | Enables or disables Voxy in all test profiles; `--dry-run` previews the file changes. |
| `test_runtime_updater_json_compat.py` | Runs focused Python unit tests for dependency metadata and profile-updater behavior; it does not launch Minecraft. |

> **Important:** `Build-CompatibilityMatrix.py` builds each target from a separate Git worktree created at the selected branch's current commit. Commit every source change you want included in the build to that branch before running it. Uncommitted edits and untracked files in your current working directory are not copied into the build worktree, so they will be missing from the resulting artifacts.

For example, to build and install Voxy artifacts for a focused set of versions, run:

```bash
python scripts/Build-CompatibilityMatrix.py --action compile --versions 1.20.1 1.20.6 1.21.1 1.21.11
```

Then update the runtime mods and shaders for those versions, and run the unit tests with:

```bash
python scripts/UpdateProfileDependencies.py --versions 1.20.1 1.20.6 1.21.1 1.21.11
python -m unittest scripts/test_runtime_updater_json_compat.py
```

Use `--branches` when you want to select source branches explicitly, and `--minecraft-directory` or `--profiles-directory` to use a non-default Minecraft installation/profile location. By default, build artifacts and logs are stored under `compatibility-builds/`, while launcher test profiles are kept in the Minecraft directory's `voxy-test-profiles/` folder.

Compilation and the Python unit tests provide build-level checks. To check rendering and runtime compatibility, launch the relevant prepared profile with `Start-MinecraftOffline.py` and test it in game; a successful build or metadata match alone does not prove in-game compatibility.

## Credits

Original project: [MCRcortex/voxy](https://github.com/MCRcortex/voxy)

Vulkan support is based on [PR #614](https://github.com/MCRcortex/voxy/pull/614) by `cochcoder`.

See [LICENSE.md](LICENSE.md) for licensing information.

