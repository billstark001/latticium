package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.latticium.planning.Revisions.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Referenced definition revisions; frozen definitions deliberately omit the live dependency. */
final class ClientIntent {
  private final Map<Key, Long> versions = new LinkedHashMap<>();

  ClientIntent(Profile profile) {
    var consistency = profile.execution().consistency();
    if (consistency.selections() == Execution.Edits.STOP)
      for (var name : SelectionReads.in(profile)) add(Domain.SELECTION, name);
    if (consistency.rules() == Execution.Edits.STOP) add(Domain.RULES, profile.id().toString());
  }

  private void add(Domain domain, String resource) {
    versions.put(new Key(domain, resource), ClientRevisions.named(domain, resource));
  }

  boolean valid() {
    for (var entry : versions.entrySet())
      if (ClientRevisions.named(entry.getKey().domain(), (String) entry.getKey().resource())
          != entry.getValue()) return false;
    return true;
  }
}
