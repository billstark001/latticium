import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Stresses the registration API used by NeoForge's parallel mod constructors, then removes
 * fixtures.
 */
public final class BridgeConcurrencyScenario implements Scenario {
  @Override
  public void run(ProbeContext context) throws Exception {
    context.require(verify(context), "This concurrency scenario requires the Forgematica target");
  }

  static boolean verify(ProbeContext context) throws Exception {
    var loader = BridgeConcurrencyScenario.class.getClassLoader();
    try {
      Class.forName("team.cagayakegirls.forgematica.Forgematica", false, loader);
    } catch (ClassNotFoundException absent) {
      return false;
    }
    var type = Class.forName("fi.dy.masa.malilib.event.InitializationHandler", true, loader);
    var contract =
        Class.forName("fi.dy.masa.malilib.interfaces.IInitializationHandler", true, loader);
    Object dispatcher = type.getMethod("getInstance").invoke(null);
    var register = type.getMethod("registerInitializationHandler", contract);
    var field = type.getDeclaredField("handlers");
    field.setAccessible(true);
    var handlers = (List<?>) field.get(dispatcher);
    int baseline = context.onClient(handlers::size);
    var fixtures = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    for (int index = 0; index < 32; index++)
      fixtures.add(
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {contract},
              (proxy, method, arguments) ->
                  switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Latticium registration fixture";
                    default -> null;
                  }));
    var start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      for (int repeat = 0; repeat < 4; repeat++)
        for (Object handler : fixtures)
          tasks.add(
              workers.submit(
                  () -> {
                    start.await();
                    register.invoke(dispatcher, handler);
                    return null;
                  }));
      start.countDown();
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
      context.require(
          context.onClient(
              () ->
                  handlers.size() == baseline + fixtures.size()
                      && fixtures.stream()
                          .allMatch(handler -> Collections.frequency(handlers, handler) == 1)),
          "Concurrent MaFgLib registration lost or duplicated handlers");
    } finally {
      workers.shutdownNow();
      if (!workers.awaitTermination(5, TimeUnit.SECONDS))
        throw new AssertionError("Registration workers did not terminate");
      context.onClient(
          () -> {
            handlers.removeIf(fixtures::contains);
            return null;
          });
    }
    context.require(
        context.onClient(() -> handlers.size() == baseline),
        "Registration fixtures were not removed");
    System.out.println(
        "LATTICIUM_AUDIT MaFgLib parallel registration: 128 calls, 32 unique handlers, no losses/duplicates, fixtures removed");
    return true;
  }
}
