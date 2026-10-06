package io.github.billstark001.latticium.mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.billstark001.latticium.mc.LatticiumClient;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Rebindable controls shared by Fabric and NeoForge. Action shortcuts require gameplay focus. */
public final class UiControls {
  public static final KeyMapping.Category CATEGORY =
      KeyMapping.Category.register(Identifier.fromNamespaceAndPath("latticium", "controls"));
  private static final KeyMapping OPEN = key("controls", InputConstants.KEY_F8);
  private static final KeyMapping PAUSE = key("pause", InputConstants.KEY_F9);
  private static final KeyMapping STOP = key("stop", InputConstants.UNKNOWN.getValue());
  private static final KeyMapping HUD = key("hud", InputConstants.UNKNOWN.getValue());

  private UiControls() {}

  private static KeyMapping key(String name, int code) {
    return new KeyMapping("key.latticium." + name, InputConstants.Type.KEYSYM, code, CATEGORY);
  }

  public static List<KeyMapping> mappings() {
    return List.of(OPEN, PAUSE, STOP, HUD);
  }

  public static void tick(Minecraft minecraft) {
    while (OPEN.consumeClick()) {
      if (minecraft.level != null && minecraft.gui.screen() == null) LatticiumScreen.open();
    }
    while (PAUSE.consumeClick()) {
      if (minecraft.level == null || minecraft.gui.screen() != null) continue;
      var client = LatticiumClient.get();
      var job = client.uiState().job();
      if (job != null && job.paused()) client.resume();
      else client.pause();
    }
    while (STOP.consumeClick()) {
      if (minecraft.level != null && minecraft.gui.screen() == null)
        LatticiumClient.get().stopAll();
    }
    while (HUD.consumeClick()) {
      if (minecraft.level == null || minecraft.gui.screen() != null) continue;
      var hud = ClientConfig.get().hud;
      hud.enabled = !hud.enabled;
      ClientConfig.save();
    }
  }
}
