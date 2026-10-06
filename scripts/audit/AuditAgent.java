import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Development-only bootstrap for a client-thread gameplay probe. */
public final class AuditAgent {
  public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
    var output = Path.of(System.getProperty("latticium.audit.output"));
    Files.createDirectories(output);
    var process = ProcessHandle.current();
    var identity = new Properties();
    identity.setProperty("pid", Long.toString(process.pid()));
    identity.setProperty(
        "started", Long.toString(process.info().startInstant().orElseThrow().toEpochMilli()));
    try (var writer = Files.newBufferedWriter(output.resolve("client-process.properties"))) {
      identity.store(writer, "Owned audit client process");
    }
    Thread worker =
        new Thread(
            () -> {
              try {
                Class<?> game = null;
                Object minecraft = null;
                long end = System.currentTimeMillis() + 600_000;
                while (minecraft == null && System.currentTimeMillis() < end) {
                  for (Class<?> type : instrumentation.getAllLoadedClasses())
                    if (type.getName().equals("net.minecraft.client.Minecraft")) {
                      game = type;
                      minecraft = type.getMethod("getInstance").invoke(null);
                      break;
                    }
                  Thread.sleep(100);
                }
                if (minecraft == null)
                  throw new IllegalStateException("Minecraft did not initialize");
                Path classes = Path.of(System.getProperty("latticium.audit.classes"));
                var loader =
                    new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()}, game.getClassLoader());
                var probe = loader.loadClass("AuditProbe");
                Runnable task = (Runnable) probe.getConstructor().newInstance();
                while (!(boolean) probe.getMethod("isDone").invoke(null)) {
                  game.getMethod("execute", Runnable.class).invoke(minecraft, task);
                  Thread.sleep(100);
                }
              } catch (Throwable failure) {
                failure.printStackTrace();
                try {
                  Files.createDirectories(output);
                  Files.writeString(output.resolve("result.txt"), "BOOTSTRAP FAIL: " + failure);
                } catch (Exception ignored) {
                }
              }
            },
            "Latticium audit probe");
    worker.setDaemon(true);
    worker.start();
  }
}
