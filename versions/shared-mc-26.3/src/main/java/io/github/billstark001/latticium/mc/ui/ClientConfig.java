package io.github.billstark001.latticium.mc.ui;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.AutoConfigClient;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.minecraft.client.gui.screens.Screen;

/** One Cloth AutoConfig model and settings screen for Fabric and NeoForge. */
@Config(name = "latticium")
public final class ClientConfig implements ConfigData {
  @ConfigEntry.Gui.Excluded private static boolean registered;

  @ConfigEntry.Gui.Tooltip public boolean pauseOnOpen = false;

  @ConfigEntry.Gui.CollapsibleObject public Hud hud = new Hud();

  @ConfigEntry.Gui.CollapsibleObject public Diagnostics diagnostics = new Diagnostics();

  public static final class Hud {
    @ConfigEntry.Gui.Tooltip public boolean enabled = true;

    @ConfigEntry.Gui.Tooltip public boolean showWhenIdle = false;

    public boolean alignRight = false;
    public boolean alignBottom = false;

    @ConfigEntry.BoundedDiscrete(min = 0, max = 256)
    public int marginX = 8;

    @ConfigEntry.BoundedDiscrete(min = 0, max = 256)
    public int marginY = 8;

    @ConfigEntry.BoundedDiscrete(min = 160, max = 480)
    public int width = 260;

    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    public int backgroundOpacity = 65;
  }

  public static final class Diagnostics {
    @ConfigEntry.Gui.Tooltip public boolean showTechnicalDetails = true;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100)
    public int refreshTicks = 10;

    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 16, max = 256)
    public int maxGroups = 64;
  }

  @Override
  public void validatePostLoad() {
    if (hud == null) hud = new Hud();
    if (diagnostics == null) diagnostics = new Diagnostics();
    hud.marginX = Math.clamp(hud.marginX, 0, 256);
    hud.marginY = Math.clamp(hud.marginY, 0, 256);
    hud.width = Math.clamp(hud.width, 160, 480);
    hud.backgroundOpacity = Math.clamp(hud.backgroundOpacity, 0, 100);
    diagnostics.refreshTicks = Math.clamp(diagnostics.refreshTicks, 1, 100);
    diagnostics.maxGroups = Math.clamp(diagnostics.maxGroups, 16, 256);
  }

  public static void register() {
    if (registered) return;
    AutoConfig.register(ClientConfig.class, GsonConfigSerializer::new);
    registered = true;
  }

  public static ClientConfig get() {
    register();
    return AutoConfig.getConfigHolder(ClientConfig.class).getConfig();
  }

  public static Screen screen(Screen parent) {
    register();
    return AutoConfigClient.getConfigScreen(ClientConfig.class, parent).get();
  }

  public static void save() {
    AutoConfig.getConfigHolder(ClientConfig.class).save();
  }
}
