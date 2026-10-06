package io.github.billstark001.latticium.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.billstark001.latticium.mc.ui.ClientConfig;

/** Optional entrypoint, loaded only by Mod Menu. */
public final class LatticiumModMenu implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return ClientConfig::screen;
  }
}
