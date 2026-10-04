package io.github.billstark001.latticium.mc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.planning.Profile;

/** Builds client command profiles using the same schema as saved profiles. */
public final class CommandProfiles {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CommandProfiles() {}

  public static String fill(ResourceId item) {
    return items("current(s{minecraft:air})", item, "deny");
  }

  public static String replace(ResourceId source, ResourceId item) {
    return items("current(s{" + source + "})", item, "selected");
  }

  public static String clear() {
    var profile = base("all()", "selected");
    profile.putObject("target").put("clear", true);
    return json(profile);
  }

  public static String query(String expression) {
    var profile = base(expression, "deny");
    profile.put("id", "user:query");
    profile.putObject("target").put("source", "latticium:query_unknown");
    return json(profile);
  }

  private static String items(String select, ResourceId item, String breakMode) {
    var profile = base(select, breakMode);
    profile.putObject("target").put("items", "{" + item + "}");
    return json(profile);
  }

  private static ObjectNode base(String select, String breakMode) {
    var profile = MAPPER.createObjectNode();
    profile.put("schema", Profile.SCHEMA_VERSION);
    profile.put("id", "user:command");
    profile.put("scope", "selection(\"build\")");
    profile.putObject("select").put("where", select);
    profile.putObject("policy").put("break", breakMode);
    return profile;
  }

  private static String json(ObjectNode profile) {
    try {
      return MAPPER.writeValueAsString(profile);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot encode command profile", error);
    }
  }
}
