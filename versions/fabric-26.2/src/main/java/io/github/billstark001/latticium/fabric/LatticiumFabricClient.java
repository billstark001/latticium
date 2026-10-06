package io.github.billstark001.latticium.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.mc.CommandProfiles;
import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.mc.ui.ClientConfig;
import io.github.billstark001.latticium.mc.ui.LatticiumHud;
import io.github.billstark001.latticium.mc.ui.LatticiumScreen;
import io.github.billstark001.latticium.mc.ui.UiControls;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Fabric lifecycle and local command surface. */
public final class LatticiumFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    ClientConfig.register();
    UiControls.mappings().forEach(KeyMappingHelper::registerKeyMapping);
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath("latticium", "status"),
        LatticiumHud::extract);
    ClientTickEvents.END_CLIENT_TICK.register(
        minecraft -> {
          LatticiumClient.get().tick(minecraft);
          UiControls.tick(minecraft);
        });
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, context) ->
            dispatcher.register(
                literal("latticium")
                    .then(
                        literal("ui")
                            .executes(
                                c -> {
                                  LatticiumScreen.open();
                                  return 1;
                                }))
                    .then(
                        literal("settings")
                            .executes(
                                c -> {
                                  var minecraft = Minecraft.getInstance();
                                  minecraft.gui.setScreen(
                                      ClientConfig.screen(minecraft.gui.screen()));
                                  return 1;
                                }))
                    .then(
                        literal("pos1")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          var pos =
                                              LatticiumClient.get()
                                                  .setCorner(Minecraft.getInstance(), 1);
                                          return "pos1 set: ("
                                              + pos.getX()
                                              + ", "
                                              + pos.getY()
                                              + ", "
                                              + pos.getZ()
                                              + ")";
                                        })))
                    .then(
                        literal("pos2")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          var pos =
                                              LatticiumClient.get()
                                                  .setCorner(Minecraft.getInstance(), 2);
                                          return "pos2 set: ("
                                              + pos.getX()
                                              + ", "
                                              + pos.getY()
                                              + ", "
                                              + pos.getZ()
                                              + ")";
                                        })))
                    .then(
                        literal("selection")
                            .then(
                                literal("save")
                                    .then(
                                        argument("name", StringArgumentType.word())
                                            .executes(
                                                c ->
                                                    run(
                                                        c.getSource(),
                                                        () -> {
                                                          var name =
                                                              StringArgumentType.getString(
                                                                  c, "name");
                                                          LatticiumClient.get()
                                                              .saveSelection(
                                                                  Minecraft.getInstance(), name);
                                                          return "Selection saved: " + name;
                                                        })))))
                    .then(
                        literal("fill")
                            .then(
                                argument("item", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  var item =
                                                      ResourceId.parse(
                                                          StringArgumentType.getString(c, "item"));
                                                  start(CommandProfiles.fill(item));
                                                  return LatticiumClient.get().status();
                                                }))))
                    .then(
                        literal("clear")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          start(CommandProfiles.clear());
                                          return LatticiumClient.get().status();
                                        })))
                    .then(
                        literal("replace")
                            .then(
                                argument("source", StringArgumentType.word())
                                    .then(
                                        argument("item", StringArgumentType.word())
                                            .executes(
                                                c ->
                                                    run(
                                                        c.getSource(),
                                                        () -> {
                                                          var source =
                                                              ResourceId.parse(
                                                                  StringArgumentType.getString(
                                                                      c, "source"));
                                                          var item =
                                                              ResourceId.parse(
                                                                  StringArgumentType.getString(
                                                                      c, "item"));
                                                          start(
                                                              CommandProfiles.replace(
                                                                  source, item));
                                                          return LatticiumClient.get().status();
                                                        })))))
                    .then(
                        literal("profile")
                            .then(
                                literal("load")
                                    .then(
                                        argument("file", StringArgumentType.word())
                                            .executes(
                                                c ->
                                                    run(
                                                        c.getSource(),
                                                        () -> {
                                                          var name =
                                                              StringArgumentType.getString(
                                                                  c, "file");
                                                          var profile = readProfile(name);
                                                          LatticiumClient.get()
                                                              .loadProfile(profile);
                                                          return "Profile loaded: " + name;
                                                        })))))
                    .then(
                        literal("enable")
                            .then(
                                argument("id", StringArgumentType.greedyString())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  var id =
                                                      ResourceId.parse(
                                                              StringArgumentType.getString(c, "id"))
                                                          .toString();
                                                  LatticiumClient.get()
                                                      .setEnabled(
                                                          Minecraft.getInstance(), id, true);
                                                  return "Enabled automatic profile: " + id;
                                                }))))
                    .then(
                        literal("disable")
                            .then(
                                argument("id", StringArgumentType.greedyString())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  var id =
                                                      ResourceId.parse(
                                                              StringArgumentType.getString(c, "id"))
                                                          .toString();
                                                  LatticiumClient.get()
                                                      .setEnabled(
                                                          Minecraft.getInstance(), id, false);
                                                  return "Disabled automatic profile: " + id;
                                                }))))
                    .then(
                        literal("start")
                            .then(
                                argument("id", StringArgumentType.greedyString())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  LatticiumClient.get()
                                                      .start(
                                                          Minecraft.getInstance(),
                                                          ResourceId.parse(
                                                                  StringArgumentType.getString(
                                                                      c, "id"))
                                                              .toString());
                                                  return LatticiumClient.get().status();
                                                }))))
                    .then(
                        literal("query")
                            .then(
                                argument("expression", StringArgumentType.greedyString())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () ->
                                                    LatticiumClient.get()
                                                        .query(
                                                            Minecraft.getInstance(),
                                                            StringArgumentType.getString(
                                                                c, "expression"),
                                                            4)
                                                        .toString()))))
                    .then(
                        literal("preview")
                            .then(
                                argument("id", StringArgumentType.greedyString())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () ->
                                                    LatticiumClient.get()
                                                        .preview(
                                                            Minecraft.getInstance(),
                                                            ResourceId.parse(
                                                                    StringArgumentType.getString(
                                                                        c, "id"))
                                                                .toString(),
                                                            4)
                                                        .toString()))))
                    .then(
                        literal("status")
                            .executes(c -> run(c.getSource(), LatticiumClient.get()::status)))
                    .then(
                        literal("refresh")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get().refresh();
                                          return "Refresh queued: "
                                              + LatticiumClient.get().status();
                                        })))
                    .then(
                        literal("pause")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get().pause();
                                          return "Paused";
                                        })))
                    .then(
                        literal("resume")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get().resume();
                                          return "Resumed";
                                        })))
                    .then(
                        literal("cancel")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get().cancel();
                                          return "Cancelled";
                                        })))));
  }

  private static void start(String json) {
    LatticiumClient.get().startInline(Minecraft.getInstance(), json);
  }

  private static String readProfile(String file) throws IOException {
    var base =
        Minecraft.getInstance()
            .gameDirectory
            .toPath()
            .resolve("config/latticium/profiles")
            .toAbsolutePath()
            .normalize();
    var path = base.resolve(file).normalize();
    if (!path.startsWith(base) || !path.getFileName().toString().endsWith(".latticium.json"))
      throw new IllegalArgumentException("Profile must be a .latticium.json file in " + base);
    return Files.readString(path);
  }

  private interface Command {
    String execute() throws Exception;
  }

  private static int run(
      net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, Command command) {
    try {
      source.sendFeedback(Component.literal(command.execute()));
      return 1;
    } catch (Exception ex) {
      source.sendError(Component.literal(ex.getMessage()));
      return 0;
    }
  }
}
