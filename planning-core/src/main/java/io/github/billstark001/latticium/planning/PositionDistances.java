package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import java.math.BigInteger;

/** Exact squared-distance ordering over the full integer coordinate range. */
public final class PositionDistances {
  // Three components at this magnitude can be squared and summed in a signed long.
  private static final long MAX_SAFE_COMPONENT = 1_700_000_000L;

  private PositionDistances() {}

  /** Returns -1 when the squared distance needs arbitrary precision. */
  public static long squaredIfLong(Position point, Position origin) {
    requireSameDimension(point, origin);
    long x = (long) point.x() - origin.x();
    long y = (long) point.y() - origin.y();
    long z = (long) point.z() - origin.z();
    if (Math.abs(x) > MAX_SAFE_COMPONENT
        || Math.abs(y) > MAX_SAFE_COMPONENT
        || Math.abs(z) > MAX_SAFE_COMPONENT) return -1;
    return x * x + y * y + z * z;
  }

  public static BigInteger squaredExact(Position point, Position origin) {
    requireSameDimension(point, origin);
    var x = BigInteger.valueOf((long) point.x() - origin.x());
    var y = BigInteger.valueOf((long) point.y() - origin.y());
    var z = BigInteger.valueOf((long) point.z() - origin.z());
    return x.multiply(x).add(y.multiply(y)).add(z.multiply(z));
  }

  /** Compares two positions by distance from one origin without overflowing. */
  public static int compare(Position left, Position right, Position origin) {
    long a = squaredIfLong(left, origin);
    long b = squaredIfLong(right, origin);
    if (a >= 0 && b >= 0) return Long.compare(a, b);
    var exactA = a >= 0 ? BigInteger.valueOf(a) : squaredExact(left, origin);
    var exactB = b >= 0 ? BigInteger.valueOf(b) : squaredExact(right, origin);
    return exactA.compareTo(exactB);
  }

  private static void requireSameDimension(Position point, Position origin) {
    if (!point.dimension().equals(origin.dimension()))
      throw new IllegalArgumentException("Cannot measure distance across dimensions");
  }
}
