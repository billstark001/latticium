# Version adapter layout

`shared-mc-26.2` and `shared-mc-26.3` will own version-specific registry binding, immutable section capture, and placement prediction. `fabric-*` and `neoforge-*` will own loader startup, game-thread action submission, observation and packaging. Common code must call the Java 21 neutral interfaces in `dsl-core` and `planning-core`.

These are layout reservations. Do not mark a target supported until its client JAR compiles, starts and passes interaction tests on that exact game and loader version.
