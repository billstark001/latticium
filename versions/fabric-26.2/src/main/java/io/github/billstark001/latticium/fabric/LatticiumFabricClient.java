package io.github.billstark001.latticium.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.mc.CommandProfiles;
import io.github.billstark001.latticium.mc.LatticiumClient;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Fabric lifecycle and local command surface. */
public final class LatticiumFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    ClientTickEvents.END_CLIENT_TICK.register(LatticiumClient.get()::tick);
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, context) ->
            dispatcher.register(
                literal("latticium")
                    .then(
                        literal("pos1")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get()
                                              .setCorner(Minecraft.getInstance(), 1);
                                          return "First corner set";
                                        })))
                    .then(
                        literal("pos2")
                            .executes(
                                c ->
                                    run(
                                        c.getSource(),
                                        () -> {
                                          LatticiumClient.get()
                                              .setCorner(Minecraft.getInstance(), 2);
                                          return "Second corner set";
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
                                argument("id", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  var id = StringArgumentType.getString(c, "id");
                                                  LatticiumClient.get()
                                                      .setEnabled(
                                                          Minecraft.getInstance(), id, true);
                                                  return "Enabled automatic profile: " + id;
                                                }))))
                    .then(
                        literal("disable")
                            .then(
                                argument("id", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  var id = StringArgumentType.getString(c, "id");
                                                  LatticiumClient.get()
                                                      .setEnabled(
                                                          Minecraft.getInstance(), id, false);
                                                  return "Disabled automatic profile: " + id;
                                                }))))
                    .then(
                        literal("start")
                            .then(
                                argument("id", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () -> {
                                                  LatticiumClient.get()
                                                      .start(
                                                          Minecraft.getInstance(),
                                                          StringArgumentType.getString(c, "id"));
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
                                argument("id", StringArgumentType.word())
                                    .executes(
                                        c ->
                                            run(
                                                c.getSource(),
                                                () ->
                                                    LatticiumClient.get()
                                                        .preview(
                                                            Minecraft.getInstance(),
                                                            StringArgumentType.getString(c, "id"),
                                                            4)
                                                        .toString()))))
                    .then(
                        literal("status")
                            .executes(c -> run(c.getSource(), LatticiumClient.get()::status)))
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
