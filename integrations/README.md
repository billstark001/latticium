# Optional target providers

The Litematica and Forgematica bridges adapt an already loaded active placement to neutral `TargetCell` and finite selection data. Each bridge loads only with its matching source mod. Air defaults to `DontCare`; explicit `include_air` maps it to `Clear`. These bridges do not read schematic files. See the [bridge development guide](../docs/bridge-development.md) for the provider contract and current validation limits.
