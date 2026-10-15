import net.minecraft.core.BlockPos;

final class FixtureNetwork {
  private FixtureNetwork() {}

  static Object call(String name, Class<?>[] signature, Object... args) throws Exception {
    var type =
        Class.forName(
            "latticiumaudit.PacketAudit",
            true,
            io.github.billstark001.latticium.mc.ClientJob.class.getClassLoader());
    return type.getMethod(name, signature).invoke(null, args);
  }

  static void begin(BlockPos at, int width, int depth) throws Exception {
    call("begin", new Class<?>[] {BlockPos.class, int.class, int.class}, at, width, depth);
  }

  static long[] end() throws Exception {
    return (long[]) call("end", new Class<?>[0]);
  }

  static String diagnostics() throws Exception {
    return (String) call("diagnostics", new Class<?>[0]);
  }
}
