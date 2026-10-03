package io.github.billstark001.latticium.planning;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;

/** Shared JSON parser that rejects duplicate keys and trailing root values. */
public final class StrictJson {
  private static final JsonMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private StrictJson() {}

  public static JsonNode parse(String text) throws IOException {
    return MAPPER.readTree(text);
  }

  public static JsonNode parse(Path path) throws IOException {
    return MAPPER.readTree(path.toFile());
  }

  public static ObjectNode emptyObject() {
    return MAPPER.createObjectNode();
  }
}
