package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.SectionScanner;
import java.util.List;
import java.util.function.Function;

/** Read-only, bounded profile previews. */
final class ClientJobPreview {
  private ClientJobPreview() {}

  static ClientJob.Preview scan(
      Profile.Bound profile,
      List<SectionScanner.SectionGroup> sections,
      Function<SectionScanner.SectionKey, Host.Capture> capture,
      int maxSections) {
    if (maxSections <= 0) throw new IllegalArgumentException("Positive preview budget required");
    int known = 0;
    int unknown = 0;
    int unavailable = 0;
    int scanned = Math.min(maxSections, sections.size());
    var scanner = new SectionScanner();
    for (int index = 0; index < scanned; index++) {
      var section = sections.get(index);
      var result = capture.apply(section.key());
      if (!(result instanceof Host.Capture.Ready ready)) {
        unavailable++;
        continue;
      }
      var mask =
          scanner.scanGroup(section, profile.scope(), profile.select(), ready.snapshot().facts());
      unknown += mask.unknownCount();
      known += mask.trueCount();
    }
    return new ClientJob.Preview(scanned, sections.size(), unavailable, known, unknown);
  }
}
