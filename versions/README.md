# Version adapter layout

`shared-mc-26.2` and `shared-mc-26.3` own version-specific registry binding, section capture, placement prediction, action submission, and server-update observation. `fabric-*` and `neoforge-*` own loader startup, client commands, and packaging. The shared adapters call the Java 21 neutral interfaces in `dsl-core` and `planning-core`.

All four main client JARs compile; [the validation record](../docs/26.2-26.3-validation.md) distinguishes title-screen and singleplayer checks from interaction tests still to run. A target should be marked supported only after its exact game and loader build passes live interaction tests.
