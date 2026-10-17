package io.github.billstark001.latticium.mc;

/** Probe-only reflection: NeoForge loads scenarios separately from the mod's runtime package. */
final class NativeAccess {
  private NativeAccess() {}

  static Object construct(String name, Class<?>[] signature, Object... arguments) {
    try {
      var constructor = type(name).getDeclaredConstructor(signature);
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (ReflectiveOperationException error) {
      throw failure(error);
    }
  }

  static Object callStatic(String name, String method, Class<?>[] signature, Object... arguments) {
    return invoke(type(name), null, method, signature, arguments);
  }

  static Object call(Object receiver, String method) {
    return invoke(receiver.getClass(), receiver, method, new Class<?>[0]);
  }

  static Object call(Object receiver, String method, Class<?>[] signature, Object... arguments) {
    return invoke(receiver.getClass(), receiver, method, signature, arguments);
  }

  static Object field(String name, String field) {
    try {
      var value = type(name).getDeclaredField(field);
      value.setAccessible(true);
      return value.get(null);
    } catch (ReflectiveOperationException error) {
      throw failure(error);
    }
  }

  static Object field(Object receiver, String field) {
    try {
      var value = receiver.getClass().getDeclaredField(field);
      value.setAccessible(true);
      return value.get(receiver);
    } catch (ReflectiveOperationException error) {
      throw failure(error);
    }
  }

  private static Object invoke(
      Class<?> type, Object receiver, String name, Class<?>[] signature, Object... arguments) {
    try {
      var method = type.getDeclaredMethod(name, signature);
      method.setAccessible(true);
      return method.invoke(receiver, arguments);
    } catch (ReflectiveOperationException error) {
      throw failure(error);
    }
  }

  private static Class<?> type(String name) {
    try {
      return Class.forName(
          "io.github.billstark001.latticium.mc." + name, true, ClientJob.class.getClassLoader());
    } catch (ClassNotFoundException error) {
      throw failure(error);
    }
  }

  private static AssertionError failure(ReflectiveOperationException error) {
    var cause =
        error instanceof java.lang.reflect.InvocationTargetException invocation
            ? invocation.getCause()
            : error;
    return new AssertionError("Native probe invocation failed", cause);
  }
}
