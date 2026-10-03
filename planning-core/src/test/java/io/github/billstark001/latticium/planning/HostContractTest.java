package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostContractTest {
  @Test
  void sectionCaptureIdentityIncludesWorldSession() {
    var section = new SectionScanner.SectionKey(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var first = new Host.SectionCaptureKey(new Host.SessionId(), section);
    var second = new Host.SectionCaptureKey(new Host.SessionId(), section);
    assertNotEquals(first, second);
    assertThrows(NullPointerException.class, () -> new Host.SectionCaptureKey(null, section));
    var epochs = new Host.Epochs(0, 0, 0, 0, 0, 0);
    assertThrows(NullPointerException.class, () -> new Host.Snapshot(null, epochs, null));
    assertThrows(NullPointerException.class, () -> new Host.Capture.Ready(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Host.Capture.Ready(new Host.Snapshot(new Host.SessionId(), epochs, null)));
    assertThrows(NullPointerException.class, () -> new Host.Submission.Accepted(null));
    assertThrows(NullPointerException.class, () -> new Host.Receipt(UUID.randomUUID(), null));
  }
}
