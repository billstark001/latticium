package io.github.billstark001.latticium.neoforge;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.mc.CommandProfiles;
import io.github.billstark001.latticium.mc.LatticiumClient;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** NeoForge client-only commands, routed into the same job controller as Fabric. */
public final class NeoCommands {
  private NeoCommands() {}

  public static void register(RegisterClientCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            literal("latticium")
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
                                                          StringArgumentType.getString(c, "name");
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
                                                      start(CommandProfiles.replace(source, item));
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
                                                          StringArgumentType.getString(c, "file");
                                                      var base =
                                                          Minecraft.getInstance()
                                                              .gameDirectory
                                                              .toPath()
                                                              .resolve("config/latticium/profiles")
                                                              .toAbsolutePath()
                                                              .normalize();
                                                      var path = base.resolve(name).normalize();
                                                      if (!path.startsWith(base)
                                                          || !name.endsWith(".latticium.json"))
                                                        throw new IllegalArgumentException(
                                                            "Invalid profile path");
                                                      LatticiumClient.get()
                                                          .loadProfile(Files.readString(path));
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
                                                  .setEnabled(Minecraft.getInstance(), id, true);
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
                                                  .setEnabled(Minecraft.getInstance(), id, false);
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
                                                              StringArgumentType.getString(c, "id"))
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
                                    }))));
  }

  private static void start(String json) {
    LatticiumClient.get().startInline(Minecraft.getInstance(), json);
  }

  private interface Command {
    String execute() throws Exception;
  }

  private static int run(CommandSourceStack source, Command command) {
    try {
      String message = command.execute();
      source.sendSuccess(() -> Component.literal(message), false);
      return 1;
    } catch (Exception ex) {
      source.sendFailure(Component.literal(ex.getMessage()));
      return 0;
    }
  }
}
