# Latticium

Latticium is a client-side Minecraft construction mod. Select a finite area, describe what should change, preview a job, and let the mod perform ordinary player interactions while checking the resulting world state. Nothing starts building merely because the mod is installed.

## What it can do

- Fill empty spaces, replace selected blocks, or clear a saved area with `/latticium` commands.
- Save a JSON profile with a typed, read-only selection expression, target and action limits.
- Pause, resume, cancel and refresh jobs; inspect blocked work with `/latticium status`.
- Open task controls with F8, pause/resume with F9, and inspect a small HUD and grouped diagnostics.
- Configure the UI through the bundled Cloth Config screen, with English, Simplified Chinese, Traditional Chinese, and Japanese translations. Fabric supports an optional Mod Menu entry; NeoForge uses its built-in mod-list settings entry.
- Use an active Litematica or Forgematica placement as a blueprint through an optional bridge mod. Latticium reads the source mod's loaded placement; it does not import `.litematic` files.

## First steps

In a world, run `/latticium pos1` at one corner and `/latticium pos2` at the opposite corner. Save the area with `/latticium selection save build`. For a small test, run `/latticium fill minecraft:stone`, then check `/latticium status`. The player needs the material, reach and permission for each action. Try a small selection first.

For profiles and blueprints, follow the [getting started guide](https://github.com/billstark001/latticium/blob/main/docs/getting-started.md) and [DSL reference](https://github.com/billstark001/latticium/blob/main/docs/dsl-reference.md).

## Versions and installation

Choose the **main Latticium JAR** matching your Minecraft version and loader: 26.2 or 26.3, Fabric or NeoForge. Java 25 is required. The main mod needs no schematic mod. To build from an active placement, install the matching **separate bridge JAR** and its source mod: Litematica on Fabric 26.2/26.3, or Forgematica on NeoForge 26.2. There is currently no validated NeoForge 26.3 bridge.

The main mod and bridges are published as separate projects. Match the bridge's Minecraft version, loader and Latticium version. Fabric builds also require Fabric API. The bridge project's dependency list identifies its source mod and supporting library.

## Current limitations

Latticium is experimental. Its current action model covers ordinary hotbar block placement and breaking, snow layers, and repeater delay interaction. Unpredictable modded placements, missing blueprint data, unloaded chunks and ambiguous overlaps are reported as blocked or unknown rather than guessed. In-world acceptance across every supported combination is still in progress; see the [validation record](https://github.com/billstark001/latticium/blob/main/docs/26.2-26.3-validation.md).

Source code, issues and developer documentation: [GitHub](https://github.com/billstark001/latticium). License: MIT.
