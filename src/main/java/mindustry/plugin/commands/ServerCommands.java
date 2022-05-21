package mindustry.plugin.commands;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Structs;
import arc.util.Timer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mindustry.content.Blocks;
import mindustry.content.Bullets;
import mindustry.content.UnitTypes;
import mindustry.core.GameState;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.MapException;
import mindustry.mod.Mods;
import mindustry.net.Administration;
import mindustry.net.Packets;
import mindustry.plugin.data.PersistentPlayerData;
import mindustry.plugin.data.PlayerData;
import mindustry.plugin.discordcommands.Command;
import mindustry.plugin.discordcommands.Context;
import mindustry.plugin.discordcommands.DiscordCommands;
import mindustry.plugin.discordcommands.RoleRestrictedCommand;
import mindustry.plugin.ioMain;
import mindustry.plugin.requests.GetMap;
import mindustry.plugin.utils.Utils;
import mindustry.plugin.utils.ranks.Rank;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.json.JSONObject;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.zip.InflaterInputStream;

import static arc.util.Log.*;
import static mindustry.Vars.*;
import static mindustry.plugin.database.Utils.*;
import static mindustry.plugin.ioMain.*;
import static mindustry.plugin.requests.IPLookup.readJsonFromUrl;
import static mindustry.plugin.utils.CustomLog.logAction;
import static mindustry.plugin.utils.LogAction.*;
import static mindustry.plugin.utils.Utils.Categories.*;
import static mindustry.plugin.utils.Utils.*;
import static mindustry.plugin.utils.ranks.Utils.listRanks;
import static mindustry.plugin.utils.ranks.Utils.rankNames;

@Deprecated
public class ServerCommands {
    private final JSONObject data;
    private final TextChannel log_channel;
    public GetMap map = new GetMap();

    public ServerCommands(JSONObject data) {
        log_channel = getTextChannel(log_channel_id);
        this.data = data;
    }

    public void registerCommands(DiscordCommands handler) {

        handler.registerCommand(new Command("maps") {
            {
                help = "Check a list of available maps and their ids.";
            }

            public void run(Context ctx) {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("**All available maps in the playlist:**");
                Seq<Map> mapList = maps.customMaps();
                for (int i = 0; i < mapList.size; i++) {
                    Map m = mapList.get(i);
                    eb.addField(escapeEverything(m.name()), m.width + " x " + m.height, true);
                }
                ctx.sendMessage(eb);
            }
        });

        if (data.has("appeal_roleid") && data.has("appeal_channel_id")) {
            String appealRole = data.getString("appeal_roleid");
            TextChannel appeal_channel = getTextChannel(data.getString("appeal_channel_id"));

            handler.registerCommand(new Command("appeal") {
                {
                    help = "Request an appeal";
                }

                public void run(Context ctx) {
                    ctx.author.asUser().get().addRole(api.getRoleById(appealRole).get());
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Successfully requested an appeal")
                            .setDescription("Please head over to <#" + appeal_channel.getIdAsString() + ">!");
                    ctx.sendMessage(eb);
                    EmbedBuilder appealMessage = new EmbedBuilder()
                            .setTitle("Please use this format to appeal:")
                            .addField("1.Names", "All names used in game.")
                            .addField("2.Screenshot", "Send a screenshot of your ban screen")
                            .addField("3.Reason", "Explain what you did and why you want to get unbanned");
                    appeal_channel.sendMessage("<@" + ctx.author.getIdAsString() + ">");
                    appeal_channel.sendMessage(appealMessage);
                }
            });
        }
        if (data.has("administrator_roleid")) {
            String adminRole = data.getString("administrator_roleid");
            // TODO: make an update command to update the EI mod

            handler.registerCommand(new RoleRestrictedCommand("config") {
                {
                    help = "Configure server settings.";
                    usage = "[name] [value...]";
                    role = adminRole;
                }

                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length == 1) {
//                        info("All config values:");
                        eb.setTitle("All config values:");
                        for (Administration.Config c : Administration.Config.all) {
//                            info("&lk| @: @", c.name(), "&lc&fi" + c.get());
//                            info("&lk| | &lw" + c.description);
//                            info("&lk|");
                            eb.addField(escapeEverything(c.name) + ": " + c.get(), c.description, true);
                        }
                        ctx.sendMessage(eb);
                        return;
                    }

                    try {
                        Administration.Config c = Administration.Config.all.find(conf -> conf.name.equalsIgnoreCase(ctx.args[1]));
                        if (ctx.args.length == 2) {
//                            info("'@' is currently @.", c.name(), c.get());
                            eb.setTitle("'" + c.name + "' is currently " + c.get() + ".");
                            ctx.sendMessage(eb);
                        } else {
                            if (c.isBool()) {
                                c.set(ctx.args[2].equals("on") || ctx.args[2].equals("true"));
                            } else if (c.isNum()) {
                                try {
                                    c.set(Integer.parseInt(ctx.args[2]));
                                } catch (NumberFormatException e) {
//                                    err("Not a valid number: @", ctx.args[2]);
                                    eb.setTitle("Not a valid Number: " + ctx.args[2]).setColor(new Color(0xff0000));
                                    ctx.sendMessage(eb);
                                    return;
                                }
                            } else if (c.isString()) {
                                c.set(ctx.args[2].replace("\\n", "\n"));
                            }

//                            info("@ set to @.", c.name(), c.get());
                            eb.setTitle(c.name + " set to " + c.get() + ".");
                            ctx.sendMessage(eb);
                            Core.settings.forceSave();
                        }
                    } catch (IllegalArgumentException e) {
//                        err("Unknown config: '@'. Run the command with no arguments to get a list of valid configs.", ctx.args[1]);
                        eb.setTitle("Error")
                                .setDescription("Unknown config: '" + ctx.args[1] + "'. Run the command with no arguments to get a list of valid configs.")
                                .setColor(new Color(0xff0000));
                        ctx.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("uploadmod") {
                {
                    help = "Upload a new mod (Include a .zip file with command message)";
                    role = adminRole;
                    usage = "<.zip attachment>";
                    category = management;
                    aliases.add("umod");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    Seq<MessageAttachment> ml = new Seq<>();
                    for (MessageAttachment ma : ctx.event.getMessageAttachments()) {
                        if (ma.getFileName().split("\\.")[ma.getFileName().split("\\.").length - 1].trim().equals("zip")) {
                            ml.add(ma);
                        }
                    }
                    if (ml.size != 1) {
                        eb.setTitle("Mod upload terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("You need to add one valid .zip file!");
                        ctx.sendMessage(eb);
                        return;
                    } else if (Core.settings.getDataDirectory().child("mods").child(ml.get(0).getFileName()).exists()) {
                        eb.setTitle("Mod upload terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("There is already a mod with this name on the server!");
                        ctx.sendMessage(eb);
                        return;
                    }
                    // more custom filename checks possible

                    CompletableFuture<byte[]> cf = ml.get(0).downloadAsByteArray();
                    Fi fh = Core.settings.getDataDirectory().child("mods").child(ml.get(0).getFileName());

                    try {
                        byte[] data = cf.get();
//                        if (!SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
//                            eb.setTitle("Mod upload terminated.");
//                            eb.setColor(Pals.error);
//                            eb.setDescription("Mod file corrupted or invalid.");
//                            ctx.sendMessage(eb);
//                            return;
//                        }
                        fh.writeBytes(cf.get(), false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    maps.reload();
                    eb.setTitle("Mod upload completed.");
                    eb.setDescription(ml.get(0).getFileName() + " was added successfully into the mod folder!\n" +
                            "Restart the server (`<restart <server>`) to activate the mod!");
                    ctx.sendMessage(eb);
//                    Utils.LogAction("uploadmap", "Uploaded a new map", ctx.author, null);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("removemod") {
                {
                    help = "Remove a mod from the folder";
                    role = adminRole;
                    usage = "<mapname/mapid>";
                    category = mapReviewer;
                    minArguments = 1;
                    aliases.add("rmod");
                }

                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    debug(ctx.message);
                    Mods.LoadedMod mod = getMod(ctx.message);
                    if (mod == null) {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Mod not found");
                        ctx.sendMessage(eb);
                        return;
                    }
                    debug(mod.file.file().getAbsoluteFile().getAbsolutePath());
                    Path path = Paths.get(mod.file.file().getAbsoluteFile().getAbsolutePath());
                    mod.dispose();

                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        e.printStackTrace();
                        eb.setTitle("There was an error performing this command.")
                                .setDescription(e.getMessage())
                                .setColor(new Color(0xff0000));
                        return;
                    }
                    if (mod.file.file().getAbsoluteFile().delete()) {
                        eb.setTitle("Command executed.");
                        eb.setDescription(mod.name + " was successfully removed from the folder.\nRestart the server to disable the mod (`<restart <server>`).");
                    } else {
                        eb.setTitle("There was an error performing this command.")
                                .setColor(new Color(0xff0000));
                    }
                    ctx.sendMessage(eb);
                }
            });


            handler.registerCommand(new RoleRestrictedCommand("enableJs") {
                {
                    help = "Enable/Disable js command for everyone.";
                    role = adminRole;
                    category = moderation;
                    usage = "<true|false>";
                }

                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    switch (ctx.args[1]) {
                        case "true", "t" -> {
                            enableJs = true;
                            if (enableJsTask != null) {
                                enableJsTask.cancel();
                            }
                            enableJsTask = Timer.schedule(() -> {
                                enableJs = false;
                                Call.sendMessage("[accent]js command disabled for everyone!");
                            }, 10 * 60);
                            Call.sendMessage("[accent]Marshal " + ctx.author.getName() + "[accent] enabled the js command for everyone! Do [cyan]/js <script...>[accent] to use it.");
                        }
                        case "false", "f" -> {
                            enableJs = false;
                            Call.sendMessage("[accent]js command disabled for everyone!");
                        }
                        default -> {
                            eb.setTitle("Error")
                                    .setColor(new Color(0xff0000))
                                    .setDescription("[scarlet]Second argument has to be true or false.");
                            ctx.sendMessage(eb);
                            return;
                        }
                    }
                    eb.setTitle((enableJs ? "Enabled" : "Disabled") + " js")
                            .setDescription((enableJs ? "Enabled" : "Disabled") + " js for everyone" + (enableJs ? " for 10 minutes." : "."))
                            .setColor(new Color((enableJs ? 0x00ff00 : 0xff0000)));
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("start") {
                {
                    help = "Restart the server. Will default to survival and a random map if not specified.";
                    role = adminRole;
                    category = management;
                    usage = "[mapname] [mode]";
                }

                public void run(Context ctx) {
                    net.closeServer();
                    state.set(GameState.State.menu);

                    // start the server again
                    EmbedBuilder eb = new EmbedBuilder();
                    Gamemode preset = Gamemode.survival;

                    if (ctx.args.length > 2) {
                        try {
                            preset = Gamemode.valueOf(ctx.args[2]);
                        } catch (IllegalArgumentException e) {
                            err("No gamemode '@' found.", ctx.args[2]);
                            eb.setTitle("Command terminated.");
                            eb.setColor(Pals.error);
                            eb.setDescription("No gamemode " + ctx.args[2] + " found.");
                            ctx.sendMessage(eb);
                            return;
                        }
                    }

                    Map result;
                    if (ctx.args.length > 1) {
                        result = getMapBySelector(ctx.args[1]);
                        if (result == null) {
                            eb.setTitle("Command terminated.");
                            eb.setColor(Pals.error);
                            eb.setDescription("Map \"" + escapeCharacters(ctx.args[1]) + "\" not found!");
                            ctx.sendMessage(eb);
                            return;
                        }
                    } else {
                        result = maps.getShuffleMode().next(preset, state.map);
                        info("Randomized next map to be @.", result.name());
                    }

                    info("Loading map...");

                    logic.reset();
//                    lastMode = preset;
//                    Core.settings.put("lastServerMode", lastMode.name());
                    try {
                        world.loadMap(result, result.applyRules(preset));
                        state.rules = result.applyRules(preset);
                        logic.play();

                        info("Map loaded.");
                        eb.setTitle("Map loaded!");
                        eb.setColor(Pals.success);
                        eb.setDescription("Hosting map: " + result.name());
                        ctx.sendMessage(eb);

                        netServer.openServer();
                    } catch (MapException e) {
                        Log.err(e.map.name() + ": " + e.getMessage());
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("e.map.name() + \": \" + e.getMessage()");
                        ctx.sendMessage(eb);
                        return;
                    }
                }
            });
        }

        if (data.has("exit_roleid")) {
            handler.registerCommand(new RoleRestrictedCommand("exit") {
                {
                    help = "Close the server.";
                    role = data.getString("exit_roleid");
                    category = management;
                }

                public void run(Context ctx) {
                    getTextChannel(log_channel_id).sendMessage(new EmbedBuilder()
                            .setTitle(ctx.author.getDisplayName() + " closed the server!")
                            .setColor(new Color(0xff0000))).join();

                    ctx.channel.sendMessage(new EmbedBuilder()
                            .setTitle("Closed the server!")
                            .setColor(new Color(0xff0000))).join();
                    net.dispose();
                    Core.app.exit();
                    System.exit(1);
                }
            });
        }

        if (data.has("apprentice_roleid")) {
            String apprenticeRole = data.getString("apprentice_roleid");

            handler.registerCommand(new RoleRestrictedCommand("mute") {
                {
                    help = "Mute a player. To unmute just use this command again.";
                    usage = "<playerid|ip|name> [reason...]";
                    minArguments = 1;
                    category = moderation;
                    role = apprenticeRole;
                    apprenticeCommand = true;
                    aliases.add("m");
                }

                @Override
                public void run(Context ctx) {
                    String target = ctx.args[1];
                    Player player = findPlayer(target);
                    EmbedBuilder eb = new EmbedBuilder();
                    if (player != null) {
                        PersistentPlayerData tdata = (playerDataGroup.getOrDefault(player.uuid(), null));
                        assert tdata != null;
                        tdata.muted = !tdata.muted;
                        eb.setTitle("Successfully " + (tdata.muted ? "muted" : "unmuted") + " " + escapeEverything(target));
                        if (ctx.args.length > 2) {
                            eb.addField("Reason", ctx.args[2]);
                        }
                        ctx.sendMessage(eb);
                        Call.infoMessage(player.con, "[cyan]You got " + (tdata.muted ? "muted" : "unmuted") + " by a moderator. " + (ctx.args.length > 2 ? "Reason: " + ctx.message.split(" ", 2)[1] : ""));
                    } else {
                        playerNotFound(target, eb, ctx);
                    }
                }
            });


            handler.registerCommand(new RoleRestrictedCommand("freeze") {
                {
                    help = "Freeze a player. To unfreeze just use this command again.";
                    usage = "<playerid|ip|name> [reason...]";
                    minArguments = 1;
                    category = moderation;
                    role = apprenticeRole;
                    apprenticeCommand = true;
                    aliases.add("f");
                }

                @Override
                public void run(Context ctx) {
                    String target = ctx.args[1];
                    Player player = findPlayer(target);
                    EmbedBuilder eb = new EmbedBuilder();
                    if (player != null) {
                        PersistentPlayerData tdata = (playerDataGroup.getOrDefault(player.uuid(), null));
                        assert tdata != null;
                        tdata.frozen = !tdata.frozen;
                        eb.setTitle("Successfully " + (tdata.frozen ? "froze" : "thawed") + " " + escapeEverything(target));
                        if (ctx.args.length > 2) {
                            eb.addField("Reason", ctx.args[2]);
                        }
                        ctx.sendMessage(eb);
                        Call.infoMessage(player.con, "[cyan]You got " + (tdata.frozen ? "frozen" : "thawed") + " by a moderator. " + (ctx.args.length > 2 ? "Reason: " + ctx.message.split(" ", 2)[1] : ""));
                    } else {
                        playerNotFound(target, eb, ctx);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("banish") {
                {
                    help = "Ban the provided player for a specific duration with a specific reason.";
                    role = apprenticeRole;
                    usage = "<player> <duration (minutes)> <reason...>";
                    category = moderation;
                    apprenticeCommand = true;
                    minArguments = 2;
                    aliases.add("b");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    String targetDuration = ctx.args[2];
                    String reason;
                    long now = Instant.now().getEpochSecond();

                    Administration.PlayerInfo info = getPlayerInfo(target);
                    if (info != null) {
                        String uuid = info.id;
                        String banId = uuid.substring(0, 4);
                        PlayerData pd = getData(uuid);
                        long until;
                        try {
                            until = now + Integer.parseInt(targetDuration) * 60L;
                            reason = ctx.message.substring(target.length() + targetDuration.length() + 2);
                        } catch (Exception e) {
//                                EmbedBuilder err = new EmbedBuilder()
//                                        .setTitle("Second argument has to be a number!")
//                                        .setColor(new Color(0xff0000));
//                                ctx.channel.sendMessage(err);
//                                return;
                            e.printStackTrace();
                            until = now + (long) (2 * 356 * 24 * 60 * 60); // 2 years
                            reason = ctx.message.substring(target.length() + 1);
                        }
                        if (pd != null) {
                            pd.bannedUntil = until;
                            pd.banReason = reason + "\n" + "[accent]Until: " + epochToString(until) + "\n[accent]Ban ID:[] " + banId;
                            setData(uuid, pd);

                            eb.setTitle("Banned " + escapeEverything(info.lastName) + " for " + targetDuration + " minutes. ");
                            eb.addField("Ban ID", banId);
                            eb.addField("For", (until - now) / 60 + " minutes.");
                            eb.addField("Until", epochToString(until));
                            eb.addInlineField("Reason", reason);
                            ctx.sendMessage(eb);

                            Player player = findPlayer(uuid);
                            if (player != null) {
                                player.con.kick(Packets.KickReason.banned);
                            }
                            logAction(ban, info, ctx, reason);
                        } else {
                            playerNotFound(target, eb, ctx);
                        }
                    } else {
                        playerNotFound(target, eb, ctx);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("alert") {
                {
                    help = "Alerts a player(s) using on-screen messages.";
                    role = apprenticeRole;
                    usage = "<playerid|ip|name|teamid> <message>";
                    category = moderation;
                    apprenticeCommand = true;
                    minArguments = 2;
                    aliases.add("a");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1].toLowerCase();

                    if (target.equals("all")) {
                        for (Player p : Groups.player) {
                            Call.infoMessage(p.con, ctx.message.split(" ", 2)[1]);
                        }
                        eb.setTitle("Command executed");
                        eb.setDescription("Alert was sent to all players.");
                        ctx.sendMessage(eb);
                    } else if (target.matches("[0-9]+") && target.length() == 1) {
                        for (Player p : Groups.player) {
                            p.sendMessage("hello", player);
                            if (p.team().id == Byte.parseByte(target)) {
                                Call.infoMessage(p.con, ctx.message.split(" ", 2)[1]);
                            }
                        }
                        eb.setTitle("Command executed");
                        eb.setDescription("Alert was sent to all players.");
                        ctx.sendMessage(eb);
                    } else {
                        Player p = findPlayer(target);
                        if (p != null) {
                            Call.infoMessage(p.con, ctx.message.split(" ", 2)[1]);
                            eb.setTitle("Command executed");
                            eb.setDescription("Alert was sent to " + escapeEverything(p));
                        } else {
                            eb.setTitle("Command terminated");
                            eb.setColor(Pals.error);
                            eb.setDescription("Player could not be found or is offline.");

                        }
                        ctx.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("info") {
                {
                    help = "Get info about a specific player.";
                    usage = "<player>";
                    role = apprenticeRole;
                    category = moderation;
                    apprenticeCommand = true;
                    minArguments = 1;
                    aliases.add("i");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    long now = Instant.now().getEpochSecond();

                    Administration.PlayerInfo info = null;
                    Player player = findPlayer(target);
                    if (player != null) {
                        info = netServer.admins.getInfoOptional(player.uuid());
                    }

                    if (info != null) {
                        eb.setTitle(escapeEverything(info.lastName) + "'s info");
                        StringBuilder s = lookup(eb, info);
                        eb.setDescription(s.toString());

                    } else {
                        eb.setTitle("Command terminated");
                        eb.setColor(Pals.error);
                        eb.setDescription("Player could not be found or is offline.");
                    }
                    ctx.sendMessage(eb);
                }
            });
        }

        if (data.has("moderator_roleid")) {
            String banRole = data.getString("moderator_roleid");

            handler.registerCommand(new RoleRestrictedCommand("gc") {
                {
                    help = "Trigger a garbage collection. Testing only.";
                    role = banRole;
                    category = management;
                }

                @Override
                public void run(Context ctx) {
                    int pre = (int) (Core.app.getJavaHeap() / 1024 / 1024);
                    System.gc();
                    int post = (int) (Core.app.getJavaHeap() / 1024 / 1024);
                    info("@ MB collected. Memory usage now at @ MB.", pre - post, post);
                    ctx.sendMessage(new EmbedBuilder()
                            .setTitle("Triggered a garbage collection!")
                            .setColor(new Color(0x00ff00))
                            .setDescription(pre - post + " MB collected. Memory usage now at " + post + " MB."));
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("subnet-ban") {
                {
                    help = "Ban a subnet. This simply rejects all connections with IPs starting with some string.";
                    usage = "[add/remove] [address]";
                    role = banRole;
                    category = moderation;
                }

                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length == 1) {
//                        info("Subnets banned: @", netServer.admins.getSubnetBans().isEmpty() ? "<none>" : "");
                        eb.setTitle("Subnets banned: " + (netServer.admins.getSubnetBans().isEmpty() ? "<none>" : ""));
                        StringBuilder sb = new StringBuilder();
                        for (String subnet : netServer.admins.getSubnetBans()) {
                            info("&lw  " + subnet);
                            sb.append("`").append(subnet).append("`\n");
                        }
                        eb.setDescription(sb.toString());
                    } else if (ctx.args.length == 2) {
//                        err("You must provide a subnet to add or remove.");
                        eb.setTitle("You must provide a subnet to add or remove.").setColor(new Color(0xff0000));
                    } else {
                        if (ctx.args[1].equals("add")) {
                            if (netServer.admins.getSubnetBans().contains(ctx.args[2])) {
//                                err("That subnet is already banned.");
                                eb.setTitle("That subnet is already banned.").setColor(new Color(0xff0000));
                                ctx.sendMessage(eb);
                                return;
                            }

                            netServer.admins.addSubnetBan(ctx.args[2]);
//                            info("Banned @**", ctx.args[2]);
                            eb.setTitle("Banned **" + ctx.args[2] + "**!").setColor(new Color(0xff0000));
                        } else if (ctx.args[1].equals("remove")) {
                            if (!netServer.admins.getSubnetBans().contains(ctx.args[2])) {
//                                err("That subnet isn't banned.");
                                eb.setTitle("That subnet isn't banned.").setColor(new Color(0xff0000));
                                ctx.sendMessage(eb);
                                return;
                            }

                            netServer.admins.removeSubnetBan(ctx.args[2]);
//                            info("Unbanned @**", ctx.args[2]);
                            eb.setTitle("Unbanned **" + ctx.args[2] + "**!").setColor(new Color(0x00ff00));
                        } else {
//                            err("Incorrect usage. Provide add/remove as the second argument.");
                            eb.setTitle("Incorrect usage. Provide add/remove as the second argument.").setColor(new Color(0xff0000));
                        }
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("say") {
                {
                    help = "Say something as the bot.";
                    usage = "<channel> <message>";
                    role = banRole;
                    category = management;
                    minArguments = 2;
                }

                @Override
                public void run(Context ctx) {
                    String channelName = ctx.args[1].replaceAll("<#", "").replaceAll(">", "");
                    String message = ctx.message.split(" ", 2)[1];
                    TextChannel channel = null;
                    if (onlyDigits(channelName)) {
                        channel = getTextChannel(channelName);
                    } else {
                        Collection<Channel> channels = api.getChannelsByName(channelName);
                        if (!channels.isEmpty()) {
                            channel = getTextChannel(String.valueOf(channels.stream().toList().get(0).getId()));
                        }
                    }
                    if (channel == null) {
                        ctx.channel.sendMessage(new EmbedBuilder()
                                .setTitle("Error")
                                .setDescription("Could not find text channel " + channelName)
                                .setColor(new Color(0xff0000)));
                        return;
                    }
                    if (!message.startsWith("```") && !message.startsWith("```json")) {
                        channel.sendMessage(new EmbedBuilder().setTitle(message));
                    } else {
                        EmbedBuilder eb;
                        try {
                            message = message.replaceAll("```json", "").replaceAll("```", "");
                            Gson gson = new GsonBuilder()
                                    .setLenient()
                                    .create();
//                            debug(message);
                            JsonElement element = gson.fromJson(message, JsonElement.class);
                            JsonObject jsonObj = element.getAsJsonObject();

                            eb = jsonToEmbed(jsonObj);
                        } catch (Exception e) {
                            e.printStackTrace();
                            ctx.sendEmbed(new EmbedBuilder()
                                    .setTitle("Error")
                                    .setColor(new Color(0xff0000))
                                    .setDescription("There was an error while parsing the json object: \n" + e.getMessage()));
                            return;
                        }
                        debug(eb.toString());
                        try {
                            channel.sendMessage(eb).get();
                        } catch (Exception e) {
                            e.printStackTrace();
                            ctx.sendEmbed(new EmbedBuilder()
                                    .setTitle("Error")
                                    .setColor(new Color(0xff0000))
                                    .setDescription("There was an error while sending the message: \n" + e.getMessage()));
                            return;
                        }
                    }
                    ctx.sendEmbed(new EmbedBuilder()
                            .setTitle("Successfully sent Message")
                            .setDescription("Successfully sent message in channel: <#" + channel.getId() + ">")
                            .setColor(new Color(0x00ff00)));
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("weapon") {
                {
                    help = "Modify the specified players weapon with the provided parameters";
                    usage = "<player> <bullet> [damage] [lifetime] [velocity]";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    ctx.args = Arrays.copyOfRange(ctx.args, 1, ctx.args.length);
                    BulletType desiredBulletType;
                    float dmg = 1f;
                    float life = 1f;
                    float vel = 1f;
                    if (ctx.args.length > 2) {
                        try {
                            dmg = Float.parseFloat(ctx.args[2]);
                        } catch (Exception e) {
                            ctx.sendEmbed(false, "Error parsing damage number");
                            return;
                        }
                    }
                    if (ctx.args.length > 3) {
                        try {
                            life = Float.parseFloat(ctx.args[3]);
                        } catch (Exception e) {
                            ctx.sendEmbed(false, "Error parsing lifetime number");
                            return;
                        }
                    }
                    if (ctx.args.length > 4) {
                        try {
                            vel = Float.parseFloat(ctx.args[4]);
                        } catch (Exception e) {
                            ctx.sendEmbed(false, "Error parsing velocity number");
                            return;
                        }
                    }
                    try {
                        Field field = Bullets.class.getDeclaredField(ctx.args[1]);
                        desiredBulletType = (BulletType) field.get(null);
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {
                        EmbedBuilder eb = new EmbedBuilder()
                                .setTitle("Invalid bullet type!")
                                .setColor(new Color(0xff0000));
                        StringBuilder validTypes = new StringBuilder("Valid types:");
                        for (Field bt : Bullets.class.getFields()) {
                            validTypes.append("`").append(bt.getName()).append("`, ");
                        }
                        eb.setDescription(validTypes.toString());
                        ctx.sendMessage(eb);
                        return;
                    }
                    HashMap<String, String> fields = new HashMap<>();
                    Player player = findPlayer(ctx.args[0]);
                    if (player != null) {
                        PlayerData pd = getData(player.uuid());
                        assert pd != null;
                        PersistentPlayerData tdata = (playerDataGroup.getOrDefault(player.uuid(), null));
                        tdata.bt = desiredBulletType;
                        tdata.sclDamage = dmg;
                        tdata.sclLifetime = life;
                        tdata.sclVelocity = vel;
                        setData(player.uuid(), pd);
                        fields.put("Bullet", ctx.args[1]);
                        fields.put("Bullet lifetime", String.valueOf(life));
                        fields.put("Bullet velocity", String.valueOf(vel));
                        ctx.sendEmbed(true, "Modded " + escapeEverything(player.name) + "'s gun", fields, true);
                    } else if (ctx.args[0].toLowerCase().equals("all")) {
                        for (Player p : Groups.player) {
                            PlayerData pd = getData(player.uuid());
                            assert pd != null;
                            PersistentPlayerData tdata = (playerDataGroup.getOrDefault(p.uuid(), null));
                            tdata.bt = desiredBulletType;
                            tdata.sclDamage = dmg;
                            tdata.sclLifetime = life;
                            tdata.sclVelocity = vel;
                            setData(p.uuid(), pd);
                        }
                        fields.put("Bullet", ctx.args[1]);
                        fields.put("Bullet lifetime", String.valueOf(life));
                        fields.put("Bullet velocity", String.valueOf(vel));
                        ctx.sendEmbed(true, "Modded everyone's gun", fields, true);
                    } else {
                        ctx.sendEmbed(false, "Can't find " + escapeEverything(ctx.args[0]));
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("fillitems") {
                {
                    help = "Fill the core with items.";
                    usage = "[team]";
                    role = banRole;
                    category = management;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (!state.is(GameState.State.playing)) {
                        err("Not playing. Host first.");
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Not playing. Host first.");
                        ctx.sendMessage(eb);
                        return;
                    }

                    Team team = ctx.args.length == 1 ? Team.sharded : Structs.find(Team.all, t -> t.name.equals(ctx.args[1]));

                    if (team == null) {
                        err("No team with that name found.");
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("No team with that name found.");
                        ctx.sendMessage(eb);
                        return;
                    }

                    if (state.teams.cores(team).isEmpty()) {
                        err("That team has no cores.");
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("That team has no cores.");
                        ctx.sendMessage(eb);
                        return;
                    }

                    for (Item item : content.items()) {
                        state.teams.cores(team).first().items.set(item, state.teams.cores(team).first().storageCapacity);
                    }

                    eb.setTitle("Core filled.");
                    eb.setColor(Pals.success);
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new Command("ranking") {
                {
                    help = "Get a ranking list.";
                    usage = "<playtime|games|buildings> [offset]";
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    // get a list from the database
                    EmbedBuilder eb = new EmbedBuilder();
                    eb.setTitle("Ranking list:");
                    int offset = 0;
                    if (ctx.args.length > 2) {
                        if (isInt(ctx.args[2])) {
                            offset = Integer.parseInt(ctx.args[2]);
                        }
                    }
                    boolean showUUID = ctx.channel.getId() != Long.parseLong(bot_channel_id) && ctx.channel.getId() != Long.parseLong(apprentice_bot_channel_id);
                    switch (ctx.args[1].toLowerCase()) {
                        case "playtime", "p" -> {
                            eb.setDescription(ranking(10, "playTime", offset, showUUID));
                        }
                        case "games", "gamesplayed", "g" -> {
                            eb.setDescription(ranking(10, "gamesPlayed", offset, showUUID));
                        }
                        case "buildings", "buildingsbuilt", "b" -> {
                            eb.setDescription(ranking(10, "buildingsBuilt", offset, showUUID));
                        }
                        case "negative", "n" -> {
                            eb.setDescription(ranking(10, "negativeRating", offset));
                        }
                        case "positive" -> {
                            eb.setDescription(ranking(10, "positiveRating", offset));
                        }
                        default -> {
                            eb.setDescription("Please select a valid stat");
                        }
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("changemap") {
                {
                    help = "Change the current map to the one provided.";
                    role = banRole;
                    usage = "<mapname/mapid>";
                    category = management;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length < 2) {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Not enough arguments, use `%changemap <mapname|mapid>`".replace("%", ioMain.prefix));
                        ctx.sendMessage(eb);
                        return;
                    }
                    Map found = getMapBySelector(ctx.message.trim());
                    if (found == null) {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Map \"" + escapeCharacters(ctx.message.trim()) + "\" not found!");
                        ctx.sendMessage(eb);
                        return;
                    }

                    changeMap(found);

                    eb.setTitle("Command executed.");
                    eb.setDescription("Changed map to " + found.name());
                    ctx.sendMessage(eb);

                    maps.reload();
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("iplookup") {
                {
                    help = "Make an ip lookup of an ip";
                    role = banRole;
                    usage = "ip";
                    category = moderation;
                    minArguments = 1;
                    hidden = true;
                    aliases.add("il");
                }

                public void run(Context ctx) {
                    String target = ctx.args[1];
                    String ip;
                    EmbedBuilder eb = new EmbedBuilder();
                    if (isValidIPAddress(target) || true) {
                        ip = ctx.args[1];
                    } else {
                        Administration.PlayerInfo info = null;
                        Player player = findPlayer(target);
                        if (player != null) {
                            info = netServer.admins.getInfo(player.uuid());
                            ip = info.lastIP;
                            ip = info.ips.get(0);
                            System.out.println(ip);
                        } else {
                            playerNotFound(target, eb, ctx);
                            return;
                        }
                    }
                    try {
                        String url = "http://api.ipapi.com/" + ip + "?access_key=" + apapi_key;
//                        System.out.println(url);
                        JSONObject json = readJsonFromUrl(url);
//                        System.out.println(json);
                        eb.setTitle(ip + "'s Lookup:");
                        eb.addField("continent", json.getString("continent_name"), true);
                        try {
                            eb.addField("Zip Code", json.getString("zip"), true);
                        } catch (Exception e) {
                            eb.addField("Zip Code", "null", true);
                        }
                        eb.addField("City", json.getString("city"), true);
                        eb.addField("Country", json.getString("country_name"), true);
                        eb.addField("Region", json.getString("region_name"), true);
                        eb.addField("Latitude", String.valueOf(Float.valueOf(BigDecimal.valueOf(json.getDouble("latitude")).floatValue())), true);
                        eb.addField("Longitude", String.valueOf(Float.valueOf(BigDecimal.valueOf(json.getDouble("longitude")).floatValue())), true);
                        ctx.sendMessage(eb);
                        System.out.println(eb);
                    } catch (Exception e) {
                        System.out.println(Arrays.toString(e.getStackTrace()));
                        eb.setTitle("There was an error executing this command: " + name + "!")
                                .setDescription(e.getStackTrace()[0].toString())
                                .setColor(Color.decode("#ff0000"));
                        ctx.sendMessage(eb);
                    }
                }
            });


            handler.registerCommand(new RoleRestrictedCommand("announce") {
                {
                    help = "Announces a message to in-game chat.";
                    role = banRole;
                    usage = "<message>";
                    category = moderation;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();

                    if (ctx.message.length() <= 0) {
                        eb.setTitle("Command terminated");
                        eb.setColor(Pals.error);
                        eb.setDescription("No message given");
                        ctx.sendMessage(eb);
                        return;
                    }

                    for (Player p : Groups.player) {
                        Call.infoMessage(p.con, ctx.message);
                    }

                    eb.setTitle("Command executed");
                    eb.setDescription("Your message was announced.");
                    ctx.sendMessage(eb);

                }
            });

            handler.registerCommand(new RoleRestrictedCommand("event") {
                {
                    help = "Changes the event command ip.";
                    role = banRole;
                    usage = "<ip/none>";
                    category = management;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();

                    if (ctx.message.length() <= 0) {
                        eb.setTitle("Command terminated");
                        eb.setColor(Pals.error);
                        eb.setDescription("No message given");
                        ctx.sendMessage(eb);
                        return;
                    }

                    if (ctx.message.toLowerCase().contains("none")) {
                        eventIp = "";
                        eb.setTitle("Command executed");
                        eb.setDescription("Event command is now disabled.");
                        ctx.sendMessage(eb);
                        return;
                    }

                    String[] m = ctx.message.split(":");
                    eventIp = m[0];
                    eventPort = Integer.parseInt(m[1]);

                    eb.setTitle("Command executed");
                    eb.setDescription("Event ip was changed to " + ctx.message);
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("admin") {
                {
                    help = "Toggle the admin status on a player.";
                    role = banRole;
                    usage = "<playerid|ip|name|teamid>";
                    category = moderation;
                    minArguments = 1;
                    hidden = true;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1].toLowerCase();


                    Player p = findPlayer(target);
                    if (p != null) {
                        Administration.PlayerInfo targetInfo = p.getInfo();
//                        Call.infoMessage(p.con, ctx.message.split(" ", 2)[1]);
//                        if (Vars.netServer.admins.getAdmins().contains(targetInfo)) {
                        if (!p.admin) {
                            netServer.admins.adminPlayer(targetInfo.id, targetInfo.adminUsid);
                            p.admin = true;
                            eb.setDescription("Promoted " + escapeEverything(p.name) + " to admin");
                        } else {
                            netServer.admins.unAdminPlayer(targetInfo.id);
                            p.admin = false;
                            eb.setDescription("Demoted " + escapeEverything(p.name) + " from admin");
                        }
                        eb.setTitle("Command executed!");
                        netServer.admins.save();
                    } else {
                        eb.setTitle("Command terminated!");
                        eb.setColor(Pals.error);
                        eb.setDescription("Player could not be found or is offline.");

                    }
                    ctx.sendMessage(eb);
                }
            });

            // command with admin remove|add
//            handler.registerCommand(new RoleRestrictedCommand("admin") {
//                {
//                    help = "Toggle the admin status on a player.";
//                    role = banRole;
//                    usage = "<add|remove> <playerid|ip|name|teamid>";
//                    category = moderation;
//                    minArguments = 1;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//
//
//                    if(!(ctx.args[1].equals("add") || ctx.args[1].equals("remove"))){
//                        err("Second parameter must be either 'add' or 'remove'.");
//                        return;
//                    }
//
//                    boolean add = ctx.args[1].equals("add");
//
//                    Administration.PlayerInfo target;
//                    Player playert = findPlayer(ctx.args[2]);
//                    if(playert != null){
//                        target = playert.getInfo();
//                    }else{
//                        target = netServer.admins.getInfoOptional(ctx.args[2]);
//                        playert = Groups.player.find(p -> p.getInfo() == target);
//                    }
//
//                    if(target != null){
//                        if(add){
// netServer.admins.adminPlayer(target.id, target.adminUsid);
//                        }else{
// netServer.admins.unAdminPlayer(target.id);
//                        }
//                        if(playert != null) playert.admin = add;
//                        eb.setTitle("Changed admin status of player: "+ escapeEverything(target.lastName));
//                        eb.setColor(Pals.success);
//                        info("Changed admin status of player: @", target.lastName);
//                    }else{
//                        eb.setTitle("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
//                        eb.setColor(Pals.error);
//                        err("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
//                    }
//                    ctx.sendMessage(eb);
//                }
//            });

            handler.registerCommand(new RoleRestrictedCommand("gameover") {
                {
                    help = "Force a game over.";
                    role = banRole;
                    category = management;
                }

                public void run(Context ctx) {
                    if (state.is(GameState.State.menu)) {
                        ctx.reply("Invalid state");
                        return;
                    }
                    Events.fire(new GameOverEvent(Team.crux));
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Command executed.")
                            .setDescription("Done. New game starting in 10 seconds.");
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("ban") {
                {
                    help = "Ban the provided player with a specific reason.";
                    usage = "<player> <reason...>";
                    role = banRole;
                    category = moderation;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    CompletableFuture.runAsync(() -> {
                        EmbedBuilder eb = new EmbedBuilder();
                        String target = ctx.args[1];
                        String reason = ctx.message.substring(target.length() + 1);

                        Administration.PlayerInfo info;
                        Player player = findPlayer(target);
                        if (player != null) {
                            info = netServer.admins.getInfo(player.uuid());
                        } else {
                            info = netServer.admins.getInfoOptional(target);
                        }

                        if (info != null) {
                            String uuid = info.id;
                            String banId = uuid.substring(0, 4);
                            PlayerData pd = getData(uuid);
                            if (pd != null) {
                                // set ban in database
                                pd.banned = true;
                                pd.banReason = reason + "\n[accent]Ban ID:[] " + banId;
                                setData(uuid, pd);

                                // send messages on discord
                                netServer.admins.banPlayerIP(info.lastIP);
                                eb.setTitle("Banned `" + escapeEverything(info.lastName) + "` permanently.");
                                eb.addField("UUID", uuid);
                                eb.addField("Ban ID", banId);
                                eb.addField("IP", info.lastIP);
                                eb.addInlineField("Reason", reason);
                                ctx.sendMessage(eb);

                                if (player != null) {
                                    player.con.kick(Packets.KickReason.banned);
                                }
                                logAction(ban, info, ctx, reason);
                            } else {
                                playerNotFound(target, eb, ctx);
                            }
                        } else {
                            playerNotFound(target, eb, ctx);
                        }
                    });
                }
            });
//
            handler.registerCommand(new RoleRestrictedCommand("blacklist") {
                {
                    help = "Ban a player by the provided uuid.";
                    usage = "<uuid> [reason]";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTimestampToNow();
                    String target = ctx.args[1];
                    String reason = ctx.args[2];
                    PlayerData pd = getData(target);
                    Administration.PlayerInfo info = netServer.admins.getInfoOptional(target);

                    if (pd != null && info != null) {
                        pd.banned = true;
                        setData(target, pd);
                        eb.setTitle("Blacklisted successfully.");
                        eb.setDescription("`" + escapeEverything(info.lastName) + "` was banned.");
                        logAction(blacklist, info, ctx, reason);
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setColor(Pals.error);
                        eb.setDescription("UUID `" + escapeEverything(target) + "` was not found in the database.");
                    }
                    ctx.sendMessage(eb);
                }
            });
//
//
            handler.registerCommand(new RoleRestrictedCommand("expel") {
                {
                    help = "Ban the provided player for a specific duration with a specific reason.";
                    usage = "<player> <duration (minutes)> [reason..]";
                    role = banRole;
                    category = moderation;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    CompletableFuture.runAsync(() -> {
                        EmbedBuilder eb = new EmbedBuilder();
                        String target = ctx.args[1];
                        String targetDuration = ctx.args[2];
                        String reason = ctx.message.substring(target.length() + targetDuration.length() + 2);
                        long now = Instant.now().getEpochSecond();

                        Player player = findPlayer(target);
                        if (player != null) {
                            String uuid = player.uuid();
                            String banId = uuid.substring(0, 4);
                            PlayerData pd = getData(uuid);
                            long until = now + Integer.parseInt(targetDuration) * 60L;
                            if (pd != null) {
//     pd.banned = true;
                                pd.bannedUntil = until;
                                pd.banReason = reason + "\n" + "[accent]Until: " + epochToString(until) + "\n[accent]Ban ID:[] " + banId;
                                setData(uuid, pd);
                            }

                            eb.setTitle("Banned `" + escapeEverything(player.name) + "` permanently.");
                            eb.addField("UUID", uuid);
                            eb.addField("Ban ID", banId);
                            eb.addField("For", targetDuration + " minutes.");
                            eb.addField("Until", epochToString(until));
                            eb.addInlineField("Reason", reason);
                            ctx.sendMessage(eb);

                            player.con.kick(Packets.KickReason.banned);
                            Administration.PlayerInfo info = netServer.admins.getInfo(player.uuid());
                            logAction(ban, info, ctx, reason);
                        } else {
                            playerNotFound(target, eb, ctx);
                        }
                    });
                }
            });
//
            handler.registerCommand(new RoleRestrictedCommand("kick") {
                {
                    help = "Kick the provided player with a specific reason.";
                    usage = "<player> [reason..]";
                    role = banRole;
                    category = moderation;
                    minArguments = 2;
                    aliases.add("k");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    String reason = ctx.message.substring(target.length() + 1);

                    Player player = findPlayer(target);
                    if (player != null) {
                        String uuid = player.uuid();
                        eb.setTitle("Kicked `" + escapeEverything(player.name) + "`.");
                        eb.addField("UUID", uuid);
                        eb.addInlineField("Reason", reason);
                        ctx.sendMessage(eb);

                        player.con.kick(Packets.KickReason.kick);
                        Administration.PlayerInfo info = netServer.admins.getInfo(player.uuid());
                        logAction(kick, info, ctx, reason);
                    } else {
                        playerNotFound(target, eb, ctx);
                        return;
                    }
                }
            });


            handler.registerCommand(new RoleRestrictedCommand("unban") {
                {
                    help = "Unban the player by the provided uuid.";
                    usage = "<uuid>";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    PlayerData pd = getData(target);

                    if (pd != null) {
                        pd.banned = false;
                        pd.bannedUntil = 0;
                        Administration.PlayerInfo info = netServer.admins.getInfo(target);
                        netServer.admins.unbanPlayerID(target);
                        eb.setTitle("Unbanned `" + escapeEverything(info.lastName) + "`.");
                        ctx.sendMessage(eb);
                        setData(target, pd);
                        logAction(unban, info, ctx, null);
                    } else {
                        eb.setTitle("UUID `" + escapeEverything(target) + "` not found in the database.");
                        eb.setColor(Pals.error);
                        ctx.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("unbanip") {
                {
                    help = "Unban the player by the provided IP.";
                    usage = "<uuid>";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    if (!netServer.admins.isIPBanned(target)) {
                        eb.setTitle("IP `" + escapeEverything(target) + "` was not banned");
                        eb.setColor(Pals.error);
                    } else {
                        netServer.admins.unbanPlayerIP(target);
                        eb.setTitle("Unbanned IP `" + escapeEverything(target) + "`");
                    }
                    ctx.sendMessage(eb);
                }
            });
//
            handler.registerCommand(new RoleRestrictedCommand("pardon") {

                {
                    help = "Unvotekickban the specified player";
                    usage = "<uuid>";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                    hidden = true;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    Administration.PlayerInfo info = netServer.admins.getInfo(target);

                    if (info != null) {
                        info.lastKicked = 0;
                        eb.setTitle("Command executed.");
                        eb.setDescription("Pardoned `" + target + "` successfully.");
                    } else {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("That ID isn't votekicked!");
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("bans") {
                {
                    help = "Show all perm bans";
                    role = banRole;
                    category = moderation;
                    aliases.add("banlist");
                }

                @Override
                public void run(Context ctx) {
                    Seq<Administration.PlayerInfo> bans = netServer.admins.getBanned();

                    EmbedBuilder eb = new EmbedBuilder();
                    eb.setTitle("Bans on " + serverName);
                    if (bans.size == 0) {
                        eb.addField("Banned players [ID]:", "No ID-banned players have been found.");
                        info("No ID-banned players have been found.");
                    } else {
                        info("Banned players [ID]:");
                        StringBuilder sb = new StringBuilder();
                        for (Administration.PlayerInfo info : bans) {
                            sb.append("`").append(info.id).append("` / ").append(escapeEverything(info.lastName)).append("\n");
                            info(" @ / Last known name: '@'", info.id, escapeEverything(info.lastName));
                        }
                        eb.addField("Banned players [ID]:", sb.toString());
                    }

                    Seq<String> ipbans = netServer.admins.getBannedIPs();

                    if (ipbans.size == 0) {
                        eb.addField("Banned players [IP]:", "No IP-banned players have been found.");
                        info("No IP-banned players have been found.");
                    } else {
                        info("Banned players [IP]:");
                        StringBuilder sb = new StringBuilder();
                        for (String string : ipbans) {
                            Administration.PlayerInfo info = netServer.admins.findByIP(string);
                            if (info != null) {
                                info("  '@' / Last known name: '@' / ID: '@'", string, info.lastName, info.id);
                                sb.append("`").append(info.id).append("` / ").append(escapeEverything(info.lastName)).append("\n");
                            } else {
                                info("  '@' (No known name or info)", string);
                                sb.append(string).append(" (No known name or info)\n");
                            }
                        }
                        eb.addField("Banned players [IP]:", sb.toString());
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("playersinfo") {
                {
                    help = "Check the information about all players on the server.";
                    role = banRole;
                    category = moderation;
                    aliases.add("pi");
                }

                public void run(Context ctx) {
                    StringBuilder msg = new StringBuilder("**Players online: " + Groups.player.size() + "**\n```\n");
                    for (Player player : Groups.player) {
                        msg.append("· ").append(escapeEverything(player.name));
                        if (!player.admin) {
                            msg.append(" : ").append(player.con.address).append(" : ").append(player.uuid()).append("\n");
                        } else {
                            msg.append(" : ").append(player.con.address).append(" : ").append(player.uuid()).append("`(admin)`\n");
                        }
                    }
                    msg.append("```");

                    StringBuilder lijst = new StringBuilder();
//                StringBuilder admins = new StringBuilder();

                    if (Groups.player.size() == 0) {
                        lijst.append("No players are currently in the server.");// + Vars.playerGroup.all().count(p->p.isAdmin)+"\n");
                    }
                    for (Player player : Groups.player) {
                        lijst.append("`* ");
                        lijst.append(String.format("%-24s : %-16s :` ", player.uuid(), player.con.address));
                        lijst.append(escapeEverything(player.name));
                        if (player.admin) lijst.append("`(admin)`");
                        lijst.append("\n");
                    }

                    new MessageBuilder()
                            .setEmbed(new EmbedBuilder()
                                    .setTitle("Players online: " + Groups.player.size())
//         .setDescription( "Info about the Server: ")
                                    .setDescription(lijst.toString())
//     .addField("Admins: ", admins+" ")
//     .addField("Players:", lijst.toString())
                                    .setColor(Color.ORANGE))
                            .send(ctx.channel);

//                    ctx.channel.sendMessage(msg.toString());
                }
            });
//
            handler.registerCommand(new RoleRestrictedCommand("lookup") {
                {
                    help = "Check all information about the specified player.";
                    usage = "<player>";
                    role = banRole;
                    category = moderation;
                    minArguments = 1;
                    aliases.add("l");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];

                    Administration.PlayerInfo info = getPlayerInfo(target);

                    if (info != null) {
                        eb.setTitle(escapeEverything(info.lastName) + "'s lookup");
                        eb.addField("UUID", info.id);
                        eb.addField("Last used ip", info.lastIP);
                        StringBuilder s = lookup(eb, info);
                        s.append("\n**All used IPs: **\n");
                        for (String ip : info.ips) {
                            s.append(escapeEverything(ip)).append(" / ");
                        }
                        eb.setDescription(s.toString());
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setColor(Pals.error);
                        eb.setDescription("Player could not be found or is offline.");
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("syncserver") {
                {
                    help = "Tell everyone to resync.\nMay kick everyone you never know!";
                    role = banRole;
                    category = management;
                }

                public void run(Context ctx) {
                    for (Player p : Groups.player) {
                        Call.worldDataBegin(p.con);
                        netServer.sendWorldData(p);
                    }
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Command executed.")
                            .setDescription("Synchronized every player's client with the server.");
                    ctx.sendMessage(eb);
                }
            });
//
            handler.registerCommand(new RoleRestrictedCommand("convert") {
                {
                    help = "Change the provided player into a specific unit.";
                    role = banRole;
                    usage = "<playerid|ip|all|name|teamid> <unit> [team]";
                    category = management;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    String target = ctx.args[1];
                    String targetMech = ctx.args[2];
//                    Mech desiredMech = Mechs.alpha;
                    UnitType desiredUnit;
                    if (target.length() > 0 && targetMech.length() > 0) {
                        try {
                            Field field = UnitTypes.class.getDeclaredField(targetMech);
                            desiredUnit = (UnitType) field.get(null);
                        } catch (NoSuchFieldException | IllegalAccessException ignored) {
                            StringBuilder sb = new StringBuilder("All available units: \n");
                            for (Field b : UnitTypes.class.getDeclaredFields()) {
                                sb.append("`").append(b.getName()).append("`,");
                            }
                            EmbedBuilder eb = new EmbedBuilder()
                                    .setTitle("Can't find Unit " + targetMech + "!")
                                    .setColor(new Color(0xff0000))
                                    .setDescription(sb.toString());
                            ctx.sendMessage(eb);
                            return;
                        }

                        EmbedBuilder eb = new EmbedBuilder();

                        if (target.equals("all")) {
                            for (Player p : Groups.player) {
                                Unit oldUnit = p.unit();
                                p.unit(desiredUnit.spawn(p.team(), p.x, p.y));
                                oldUnit.kill();
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed everyone's unit into " + desiredUnit.name);
                            ctx.sendMessage(eb);
                            return;
                        } else if (target.matches("[0-9]+") && target.length() == 1) {
                            for (Player p : Groups.player) {
                                if (p.team().id == Byte.parseByte(target)) {
                                    Unit oldUnit = p.unit();
                                    p.unit(desiredUnit.spawn(p.team(), p.x, p.y));
                                    oldUnit.kill();
                                }
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed everyone's unit into " + desiredUnit.name);
                            ctx.sendMessage(eb);
                            return;
                        }
                        Player player = findPlayer(target);
                        if (player != null) {
                            Unit oldUnit = player.unit();
                            player.unit(desiredUnit.spawn(player.team(), player.x, player.y));
                            oldUnit.kill();
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed " + escapeEverything(player.name) + "s unit into " + desiredUnit.name);
                            ctx.sendMessage(eb);
                        }
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("team") {
                {
                    help = "Change the provided player's team into the provided one.";
                    role = banRole;
                    usage = "<playerid|ip|all|name|teamid> <team>";
                    category = management;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    String target = ctx.args[1];
                    String targetTeam = ctx.args[2];
                    Team desiredTeam = Team.crux;
                    EmbedBuilder eb = new EmbedBuilder();

                    if (target.length() > 0 && targetTeam.length() > 0) {
                        try {
                            Field field = Team.class.getDeclaredField(targetTeam);
                            desiredTeam = (Team) field.get(null);
                        } catch (NoSuchFieldException | IllegalAccessException ignored) {
                            if (isInt(targetTeam)) {
                                desiredTeam = Team.get(Integer.parseInt(ctx.args[2]));
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setColor(Pals.error);
                                eb.setDescription("Please select a valid team");
                                ctx.sendMessage(eb);
                                return;
                            }
                        }

                        if (target.equals("all")) {
                            for (Player p : Groups.player) {
                                p.team(desiredTeam);
//     p.spawner = getCore(p.getTeam());
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed everyone's team to " + desiredTeam.name);
                            ctx.sendMessage(eb);
                            return;
                        } else if (target.matches("[0-9]+") && target.length() == 1) {
                            for (Player p : Groups.player) {
                                if (p.team().id == Byte.parseByte(target)) {
                                    p.team(desiredTeam);
//         p.spawner = getCore(p.getTeam());
                                }
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed everyone's team to " + desiredTeam.name);
                            ctx.sendMessage(eb);
                            return;
                        }
                        Player player = findPlayer(target);
                        if (player != null) {
                            player.team(desiredTeam);
// player.spawner = getCore(player.getTeam());
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed " + escapeEverything(player.name) + "s team to " + desiredTeam.name);
                            ctx.sendMessage(eb);
                        } else {
                            eb.setTitle("Command terminated");
                            eb.setColor(Pals.error);
                            eb.setDescription("Player " + escapeEverything(target) + " could not be found or is offline.");
                            ctx.sendMessage(eb);
                        }
                    }
                }
            });

//            handler.registerCommand(new RoleRestrictedCommand("changeteamid") {
//                {
//                    help = "Change the provided player's team into a generated int.";
//                    role = banRole;
//                    usage = "<playerid|ip|all|name> <team>";
//                    category = management;
//                    minArguments = 2;
//                }
//
//                public void run(Context ctx) {
//                    String target = ctx.args[1];
//                    int targetTeam = Integer.parseInt(ctx.args[2]);
//                    if (target.length() > 0 && targetTeam > 0) {
//                        EmbedBuilder eb = new EmbedBuilder();
//
//                        if (target.equals("all")) {
// for (Player p : Groups.player) {
//     p.team(Team.get(targetTeam));
////     p.spawner = getCore(p.getTeam());
// }
// eb.setTitle("Command executed successfully.");
// eb.setDescription("Changed everyone's team to " + targetTeam);
// ctx.sendMessage(eb);
// return;
//                        } else if (target.matches("[0-9]+") && target.length() == 1) {
// for (Player p : Groups.player) {
//     if (p.team().id == Byte.parseByte(target)) {
//         p.team(Team.get(targetTeam));
////         p.spawner = getCore(p.getTeam());
//     }
// }
// eb.setTitle("Command executed successfully.");
// eb.setDescription("Changed everyone's team to " + targetTeam);
// ctx.sendMessage(eb);
// return;
//                        }
//                        Player player = findPlayer(target);
//                        if (player != null) {
// player.team(Team.get(targetTeam));
// eb.setTitle("Command executed successfully.");
// eb.setDescription("Changed " + escapeEverything(player.name) + "s team to " + targetTeam);
// ctx.sendMessage(eb);
//                        }
//                    }
//                }
//            });

            handler.registerCommand(new RoleRestrictedCommand("rename") {
                {
                    help = "Rename the provided player";
                    role = banRole;
                    usage = "<playerid|ip|name> <name>";
                    category = management;
                    minArguments = 2;
                    aliases.add("r");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    String name = ctx.message.substring(target.length() + 1);
                    if (target.length() > 0 && name.length() > 0) {
                        Player player = findPlayer(target);
                        if (player != null) {
                            player.name = name;
// PersistentPlayerData tdata = ioMain.playerDataGroup.get(player.uuid);
// if (tdata != null) tdata.origName = name;
                            eb.setTitle("Command executed successfully");
                            eb.setDescription("Changed name to " + escapeEverything(player.name));
                            ctx.sendMessage(eb);
                            Call.infoMessage(player.con, "[scarlet]Your name was changed by a moderator.");
                        }
                    }
                }

            });

            handler.registerCommand(new RoleRestrictedCommand("motd") {
                {
                    help = "Change / set a welcome message";
                    role = banRole;
                    usage = "<newmessage>";
                    category = management;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    eb.setTitle("Command executed successfully");
                    String message = ctx.message;
                    if (message.length() > 0 && !message.equals("disable")) {
                        welcomeMessage = message;
                        Core.settings.put("welcomeMessage", message);
                        Core.settings.autosave();
                        eb.setDescription("Changed welcome message.");
                        ctx.sendMessage(eb);
                    } else {
                        eb.setDescription("Disabled welcome message.");
                        ctx.sendMessage(eb);
                    }
                }

            });

            handler.registerCommand(new RoleRestrictedCommand("screenmessage") {
                {
                    help = "List, remove or add on-screen messages.";
                    role = banRole;
                    usage = "<list/remove/add> <message>";
                    category = moderation;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1].toLowerCase();
                    String message = "";
                    if (!target.equals("list")) {
                        message = ctx.message.split(" ", 2)[1];
                    }

                    switch (target) {
                        case "list" -> {
                            eb.setTitle("All on-screen messages:");
                            for (String msg : onScreenMessages) {
                                eb.addField(String.valueOf(onScreenMessages.indexOf(msg)), msg);
                            }
                            ctx.sendMessage(eb);
                        }
                        case "remove" -> {
                            if (onScreenMessages.get(Integer.parseInt(message.trim())) != null) {
                                onScreenMessages.remove(Integer.parseInt(message.trim()));
                                eb.setTitle("Command executed");
                                eb.setDescription("Removed provided on-screen message.");
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setDescription("That on-screen message does not exist.");
                                eb.setColor(Pals.error);
                            }
                            ctx.sendMessage(eb);
                        }
                        case "add" -> {
                            if (message.length() > 0) {
                                onScreenMessages.add(message);
                                eb.setTitle("Command executed");
                                eb.setDescription("Added on-screen message `" + message + "`.");
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setDescription("On-screen messages must be longer than 0 characters.");
                                eb.setColor(Pals.error);
                            }
                            ctx.sendMessage(eb);
                        }
                        default -> {
                            eb.setTitle("Command terminated");
                            eb.setDescription("Invalid arguments provided.");
                            eb.setColor(Pals.error);
                            ctx.sendMessage(eb);
                        }
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("edit") {
                {
                    help = "Change / set a message";
                    role = banRole;
                    usage = "<stats|rule|info> <new message>";
                    category = management;
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    String target = ctx.args[1].toLowerCase();
                    switch (target) {
                        case "stats", "s" -> {
                            EmbedBuilder eb = new EmbedBuilder();
                            eb.setTitle("Command executed successfully");
                            String message = ctx.message.split(" ", 2)[1];
                            if (message.length() > 0) {
                                System.out.println("new stat message: " + message);
                                statMessage = message;
                                Core.settings.put("statMessage", message);
                                Core.settings.autosave();
                                eb.setDescription("Changed stat message.");
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setDescription("No message provided.");
                            }
                            ctx.sendMessage(eb);
                        }
                        case "rule", "r" -> {
                            EmbedBuilder eb = new EmbedBuilder();
                            eb.setTitle("Command executed successfully");
                            String message = ctx.message.split(" ", 2)[1];
                            if (message != null) {
                                ruleMessage = message;
                                Core.settings.put("ruleMessage", message);
                                Core.settings.autosave();
                                eb.setDescription("Changed rules.");
                                eb.setColor(Pals.success);
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setDescription("No message provided.");
                                eb.setColor(Pals.error);
                            }
                            ctx.sendMessage(eb);
                        }
                        case "info", "i" -> {
                            EmbedBuilder eb = new EmbedBuilder();
                            eb.setTitle("Command executed successfully");
                            String message = ctx.message.split(" ", 2)[1];
                            if (message.length() > 0) {
                                System.out.println("new info message: " + message);
                                infoMessage = message;
                                Core.settings.put("infoMessage", message);
                                Core.settings.autosave();
                                eb.setDescription("Changed info message.");
                            } else {
                                eb.setTitle("Command terminated");
                                eb.setDescription("No message provided.");
                            }
                            ctx.sendMessage(eb);
                        }
                        default -> {
                            EmbedBuilder eb = new EmbedBuilder();
                            eb.setTitle("Please select a message to edit!");
                            eb.setColor(Pals.error);
                            ctx.sendMessage(eb);
                        }
                    }
                }
            });

            // idk why these commands are still here

//            handler.registerCommand(new RoleRestrictedCommand("statmessage") {
//                {
//                    help = "Change / set a stat message";
//                    role = banRole;
//                    usage = "<newmessage>";
//                    category = management;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//                    eb.setTitle("Command executed successfully");
//                    String message = ctx.message;
//                    if (message.length() > 0) {
//                        statMessage = message;
//                        Core.settings.put("statMessage", message);
//                        Core.settings.autosave();
//                        eb.setDescription("Changed stat message.");
//                    } else {
//                        eb.setTitle("Command terminated");
//                        eb.setDescription("No message provided.");
//                    }
//                    ctx.sendMessage(eb);
//                }
//
//            });

//            handler.registerCommand(new RoleRestrictedCommand("reqMessage") {
//                {
//                    help = "Change / set a requirement Message";
//                    role = banRole;
//                    usage = "<newmessage>";
//                    category = management;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//                    eb.setTitle("Command executed successfully");
//                    String message = ctx.message;
//                    if (message.length() > 0) {
//                        reqMessage = message;
//                        Core.settings.put("reqMessage", message);
//                        Core.settings.autosave();
//                        eb.setDescription("Changed reqMessage.");
//                    } else {
//                        eb.setTitle("Command terminated");
//                        eb.setDescription("No message provided.");
//                    }
//                    ctx.sendMessage(eb);
//                }
//
//            });
//
//            handler.registerCommand(new RoleRestrictedCommand("rankMessage") {
//                {
//                    help = "Change / set a rank Message";
//                    role = banRole;
//                    usage = "<newmessage>";
//                    category = management;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//                    eb.setTitle("Command executed successfully");
//                    String message = ctx.message;
//                    if (message.length() > 0) {
//                        rankMessage = message;
//                        Core.settings.put("rankMessage", message);
//                        Core.settings.autosave();
//                        eb.setDescription("Changed rankMessage.");
//                    } else {
//                        eb.setTitle("Command terminated");
//                        eb.setDescription("No message provided.");
//                    }
//                    ctx.sendMessage(eb);
//                }
//
//            });

//            handler.registerCommand(new RoleRestrictedCommand("rulemessage") {
//                {
//                    help = "Change server rules. Use approriate prefix";
//                    role = banRole;
//                    category = management;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//                    eb.setTitle("Command executed successfully");
//                    String message = ctx.message;
//                    if (message != null) {
//                        ruleMessage = message;
//                        Core.settings.put("ruleMessage", message);
//                        Core.settings.autosave();
//                        eb.setDescription("Changed rules.");
//                        eb.setColor(Pals.success);
//                    } else {
//                        eb.setTitle("Command terminated");
//                        eb.setDescription("No message provided.");
//                        eb.setColor(Pals.error);
//                    }
//                    ctx.sendMessage(eb);
//                }
//
//            });


            handler.registerCommand(new RoleRestrictedCommand("spawn") {
                {
                    help = "Spawn x units at the location of the specified player";
                    role = banRole;
                    category = management;
                    usage = "<playerid|ip|name> <unit> <amount>";
                    minArguments = 3;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    String targetUnit = ctx.args[2];
                    int amount = Integer.parseInt(ctx.args[3]);
                    UnitType desiredUnit = UnitTypes.dagger;
                    if (target.length() > 0 && targetUnit.length() > 0 && amount > 0 && amount < 1000) {
                        try {
                            Field field = UnitTypes.class.getDeclaredField(targetUnit);
                            desiredUnit = (UnitType) field.get(null);
                        } catch (NoSuchFieldException | IllegalAccessException ignored) {
                        }

                        Player player = findPlayer(target);
                        if (player != null) {
                            UnitType finalDesiredUnit = desiredUnit;
                            IntStream.range(0, amount).forEach(i -> {
                                Unit unit = finalDesiredUnit.create(player.team());
                                unit.set(player.getX(), player.getY());
                                unit.add();
                            });
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Spawned " + amount + " " + targetUnit + " near " + Utils.escapeEverything(player.name) + ".");
                            ctx.sendMessage(eb);
                        }
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Invalid arguments provided.");
                        eb.setColor(Pals.error);
                        ctx.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("killunits") {
                {
                    help = "Kills all units of the team of the specified player";
                    role = banRole;
                    category = management;
                    usage = "<playerid|ip|name> <unit>";
                    minArguments = 2;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    String targetUnit = ctx.args[2];
                    UnitType desiredUnit = UnitTypes.dagger;
                    if (target.length() > 0 && targetUnit.length() > 0) {
                        try {
                            Field field = UnitTypes.class.getDeclaredField(targetUnit);
                            desiredUnit = (UnitType) field.get(null);
                        } catch (NoSuchFieldException | IllegalAccessException ignored) {
                        }

                        Player player = findPlayer(target);
                        if (player != null) {
                            int amount = 0;
                            for (Unit unit : Groups.unit) {
                                if (unit.team == player.team()) {
                                    if (unit.type == desiredUnit) {
                                        unit.kill();
                                        amount++;
                                    }
                                }
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Killed " + amount + " " + targetUnit + "s on team " + player.team());
                            ctx.sendMessage(eb);
                        }
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Invalid arguments provided.");
                        eb.setColor(Pals.error);
                        ctx.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("setblock") {
                {
                    help = "Create a block at the player's current location and on the player's current team.";
                    role = banRole;
                    usage = "<playerid|ip|name> <block> [rotation]";
                    category = management;
                    minArguments = 2;
                    aliases.add("sb");
                }

                public void run(Context ctx) {
                    String target = ctx.args[1];
                    String targetBlock = ctx.args[2];
                    int targetRotation = 0;
                    if (ctx.args.length >= 4) {
                        if (ctx.args[3] != null && !ctx.args[3].equals("")) {
                            targetRotation = Integer.parseInt(ctx.args[3]);
                        }
                    }
                    Block desiredBlock;

                    try {
                        Field field = Blocks.class.getDeclaredField(targetBlock);
                        desiredBlock = (Block) field.get(null);
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {
                        StringBuilder sb = new StringBuilder("All available blocks: \n");
                        for (Field b : Blocks.class.getDeclaredFields()) {
                            sb.append("`").append(b.getName()).append("`,");
                        }
                        EmbedBuilder eb = new EmbedBuilder()
                                .setTitle("Can't find Block " + targetBlock + "!")
                                .setColor(new Color(0xff0000))
                                .setDescription(sb.toString());
                        ctx.sendMessage(eb);
                        return;
                    }

                    EmbedBuilder eb = new EmbedBuilder();
                    Player player = findPlayer(target);

                    if (player != null) {
                        float x = player.getX();
                        float y = player.getY();
                        Tile tile = world.tileWorld(x, y);
                        try {
                            tile.setNet(desiredBlock, player.team(), targetRotation);
                        } catch (Exception e) {
                            eb.setTitle("There was an error trying to execute this command!");
                            eb.setDescription("Error: " + e);
                            eb.setColor(Pals.error);
                            ctx.sendMessage(eb);
                            return;
                        }
                        eb.setTitle("Command executed successfully.");
                        eb.setDescription("Spawned " + desiredBlock.name + " on " + Utils.escapeEverything(player.name) + "'s position.");
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Invalid arguments provided.");
                        eb.setColor(Pals.error);
                    }
                    ctx.sendMessage(eb);
                }
            });

//            handler.registerCommand(new RoleRestrictedCommand("weaponmod") { // OH NO
//                {
//                    help = "<playerid|ip|name|all(oh no)> <bullet-type> <lifetime-modifier> <velocity-modifier> Mod the current weapon of a player.";
//                    role = banRole;
//                }
//
//                public void run(Context ctx) {
//                    EmbedBuilder eb = new EmbedBuilder();
//
//                    String target = ctx.args[1];
//                    String targetBullet = ctx.args[2];
//                    float targetL = Float.parseFloat(ctx.args[3]);
//                    float targetV = Float.parseFloat(ctx.args[4]);
//                    BulletType desiredBullet = null;
//
//                    if (target.length() > 0 && targetBullet.length() > 0) {
//                        try {
// Field field = Bullets.class.getDeclaredField(targetBullet);
// desiredBullet = (BulletType) field.get(null);
//                        } catch (NoSuchFieldException | IllegalAccessException ignored) {
//                        }
//
//                        if (target.equals("all")) {
// for (Player p : Groups.player) {
//     if (player != null) { // what???    ...    how does this happen
//         try {
//             if (desiredBullet == null) {
//                 BulletType finalDesiredBullet = desiredBullet;
//                 Arrays.stream(player.unit().mounts).forEach(u -> u.bullet.type = finalDesiredBullet);
//                 Arrays.stream(player.unit().mounts).
//                         player.bt = null;
//             } else {
//                 player.bt = desiredBullet;
//                 player.sclLifetime = targetL;
//                 player.sclVelocity = targetV;
//             }
//         } catch (Exception ignored) {
//         }
//     }
// }
// eb.setTitle("Command executed");
// eb.setDescription("Changed everyone's weapon mod. sorry. i dont know how to explain the rest");
// ctx.sendMessage(eb);
//                        }
//
//                        Player player = findPlayer(target);
//                        if (player != null) {
// if (desiredBullet == null) {
//     player.bt = null;
//     eb.setTitle("Command executed");
//     eb.setDescription("Reverted " + escapeCharacters(player.name) + "'s weapon to default.");
//     ctx.sendMessage(eb);
// } else {
//     player.bt = desiredBullet;
//     player.sclLifetime = targetL;
//     player.sclVelocity = targetV;
//     eb.setTitle("Command executed");
//     eb.setDescription("Modded " + escapeCharacters(player.name) + "'s weapon to " + targetBullet + " with " + targetL + "x lifetime modifier and " + targetV + "x velocity modifier.");
//     ctx.sendMessage(eb);
// }
//                        }
//                    } else {
//                        eb.setTitle("Command terminated");
//                        eb.setDescription("Invalid arguments provided.");
//                        eb.setColor(Pals.error);
//                        ctx.sendMessage(eb);
//                    }
//                }
//            });


            handler.registerCommand(new RoleRestrictedCommand("js") {
                {
                    help = "Run a js command!";
                    usage = "<code>";
                    role = banRole;
                    category = management;
                    hidden = true;
                    minArguments = 1;
                }

                public void run(Context ctx) {
                    Core.app.post(() -> {
                        EmbedBuilder eb = new EmbedBuilder();
                        eb.setTitle("Command executed successfully!");
                        System.out.println(ctx.message);
                        eb.setDescription("Output: " + mods.getScripts().runConsole(ctx.message));
                        ctx.sendMessage(eb);
                    });
                }
            });


            handler.registerCommand(new RoleRestrictedCommand("setrank") {
                {
                    help = "Change the player's rank to the provided one.\nList of all ranks" + listRanks();
                    usage = "<playerid|ip|name> <rank>";
                    role = banRole;
                    category = management;
                    minArguments = 2;
                    aliases.add("sr");
                }

                public void run(Context ctx) {
                    CompletableFuture.runAsync(() -> {
                        EmbedBuilder eb = new EmbedBuilder();
                        String target = ctx.args[1];
                        String targetRankString = ctx.args[2];
                        int targetRank = -1;
                        if (!onlyDigits(targetRankString)) {
                            // try to get it by name
                            for (java.util.Map.Entry<Integer, Rank> rank : rankNames.entrySet()) {
                                if (rank.getValue().name.toLowerCase(Locale.ROOT).startsWith(targetRankString.toLowerCase(Locale.ROOT))) {
                                    targetRank = rank.getKey();
                                    break;
                                }
                            }
                        } else {
                            try {
                                targetRank = Integer.parseInt(ctx.args[2]);
                            } catch (Exception ignored) {
                            }
                        }
                        if (targetRank == -1) {
                            ctx.sendEmbed(new EmbedBuilder()
                                    .setTitle("Error")
                                    .setColor(new Color(0xff0000))
                                    .setDescription("Could not find rank " + targetRankString));
                            return;
                        }
                        if (targetRank > rankNames.size() - 1 || targetRank < 0) {
                            eb.setTitle("Error")
                                    .setDescription("Rank has to be larger than -1 and smaller than " + (rankNames.size() - 1) + "!")
                                    .setColor(new Color(0xff0000));
                            ctx.sendMessage(eb);
                            return;
                        }
                        if (target.length() > 0 && targetRank > -1) {
                            Player player = findPlayer(target);
                            String uuid = null;
                            if (player == null) {
                                uuid = target;
                            } else {
                                uuid = player.uuid();
                            }

                            PlayerData pd = getData(uuid);
                            if (pd != null) {
                                pd.rank = targetRank;
                                setData(uuid, pd);
                                Administration.PlayerInfo info = null;
                                if (player != null) {
                                    info = netServer.admins.getInfo(player.uuid());
                                } else {
                                    info = netServer.admins.getInfoOptional(target);
                                }
                                eb.setTitle("Command executed successfully");
                                eb.setDescription("Promoted " + escapeEverything(info.names.get(0)) + " to " + rankNames.get(targetRank).name);
                                ctx.sendMessage(eb);
                                int rank = pd.rank;
                                if (player != null) {
                                    player.name = rankNames.get(rank).tag + player.name.replaceAll(" ", "").replaceAll("<.*?>", "").replaceAll("\\|(.*)\\|", "");
                                }
                                logAction(setRank, info, ctx, null);
                            } else {
                                playerNotFound(target, eb, ctx);
                                return;
                            }

                            if (targetRank == rankNames.size() - 1 && player != null) {
                                netServer.admins.adminPlayer(player.uuid(), player.usid());
                            }
                        }
                    });
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("ip") {
                {
                    help = "Ip tools";
                    usage = "<check|ban|unban> <ip> [reason]";
                    category = moderation;
                    role = banRole;
                    minArguments = 2;
                }

                @Override
                public void run(Context ctx) {
                    String op = ctx.args[1];
                    String targetIp = ctx.args[2];
                    String reason = null;
                    if (ctx.args.length > 3) {
                        reason = ctx.message.split(" ", 3)[2];
                    }
                    EmbedBuilder eb = new EmbedBuilder();

                    switch (op) {
                        case "check", "c" -> {
                            Seq<String> bans = netServer.admins.getBannedIPs();
                            eb.setTitle(targetIp + (bans.contains(targetIp) ? "is" : "is not") + " banned");
                        }
                        case "ban", "b" -> {
                            netServer.admins.banPlayerIP(targetIp);
                            eb.setTitle("Banned " + targetIp)
                                    .setColor(new Color(0xff0000));
                            for (Player player : Groups.player) {
                                if (netServer.admins.isIDBanned(player.uuid()) || netServer.admins.isIPBanned(player.uuid())) {
                                    Call.sendMessage("[scarlet]" + player.name + " has been banned.");
                                    player.con.kick(Packets.KickReason.banned);
                                }
                            }
                            logAction(ipBan, ctx, reason, targetIp);
                        }
                        case "unban", "u", "ub" -> {
                            if (netServer.admins.unbanPlayerIP(targetIp) || netServer.admins.unbanPlayerID(targetIp)) {
                                eb.setTitle("Unbanned Ip " + targetIp)
                                        .setColor(new Color(0x00ff00));
                                info("Unbanned player: @", targetIp);
                                logAction(ipUnban, ctx, reason, targetIp);
                            } else {
                                err("That IP is not banned!");
                                eb.setTitle("That IP/ID is not banned!")
                                        .setColor(new Color(0xff0000));
                            }
                        }
                    }
                    ctx.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("setstats") {
                {
                    help = "Change the player's statistics to the provided one.";
                    usage = "<playerid|ip|name> <rank> <playTime> <buildingsBuilt> <gamesPlayed>";
                    role = banRole;
                    category = management;
                    minArguments = 5;
                }

                public void run(Context ctx) {
                    CompletableFuture.runAsync(() -> {
                        EmbedBuilder eb = new EmbedBuilder();
                        String target = ctx.args[1];
                        int targetRank = Integer.parseInt(ctx.args[2]);
                        int playTime = Integer.parseInt(ctx.args[3]);
                        int buildingsBuilt = Integer.parseInt(ctx.args[4]);
                        int gamesPlayed = Integer.parseInt(ctx.args[5]);
                        if (target.length() > 0 && targetRank > -1) {
// Player player = findPlayer(target);
// if (player == null) {
//     eb.setTitle("Command terminated");
//     eb.setDescription("Player not found.");
//     eb.setColor(Pals.error);
//     ctx.sendMessage(eb);
//     return;
// }

                            Administration.PlayerInfo info = null;
                            Player player = findPlayer(target);
                            if (player != null) {
                                info = netServer.admins.getInfo(player.uuid());
                            } else {
                                info = netServer.admins.getInfoOptional(target);
                            }
                            if (info == null) {
                                playerNotFound(target, eb, ctx);
                                return;
                            }
                            PlayerData pd = getData(info.id);
                            if (pd != null) {
                                pd.buildingsBuilt = buildingsBuilt;
                                pd.gamesPlayed = gamesPlayed;
                                pd.playTime = playTime;
                                pd.rank = targetRank;
                                setData(info.id, pd);
                                eb.setTitle("Command executed successfully");
                                eb.setDescription(String.format("Set stats of %s to:\nPlaytime: %d\nBuildings built: %d\nGames played: %d", escapeEverything(player.name), playTime, buildingsBuilt, gamesPlayed));
//     eb.setDescription("Promoted " + escapeCharacters(player.name) + " to " + targetRank);
                                ctx.sendMessage(eb);
//     player.con.kick("Your rank was modified, please rejoin.", 0);
                            } else {
                                playerNotFound(target, eb, ctx);
                                return;
                            }

                            if (targetRank == 6)
                                netServer.admins.adminPlayer(player.uuid(), player.usid());
                        }
                    });
                }

            });

        }

            /*handler.registerCommand(new Command("sendm"){ // use sendm to send embed messages when needed locally, disable for now
                public void run(Context ctx){
                    EmbedBuilder eb = new EmbedBuilder()
 .setColor(Utils.Pals.info)
 .setTitle("Support mindustry.io by donating, and receive custom ranks!")
 .setUrl("https://donate.mindustry.io/")
 .setDescription("By donating, you directly help me pay for the monthly server bills I receive for hosting 4 servers with **150+** concurrent players daily.")
 .addField("VIP", "**VIP** is obtainable through __nitro boosting__ the server or __donating $1.59+__ to the server.", false)
 .addField("__**MVP**__", "**MVP** is a more enchanced **vip** rank, obtainable only through __donating $3.39+__ to the server.", false)
 .addField("Where do I get it?", "You can purchase **vip** & **mvp** ranks here: https://donate.mindustry.io", false)
 .addField("\uD83E\uDD14 io is pay2win???", "Nope. All perks vips & mvp's gain are aesthetic items **or** items that indirectly help the team. Powerful commands that could give you an advantage are __disabled on pvp.__", true);
                    ctx.sendMessage(eb);
                }
            });*/


        if (data.has("mapSubmissions_roleid")) {
            String reviewerRole = data.getString("mapSubmissions_roleid");
            handler.registerCommand(new RoleRestrictedCommand("uploadmap") {
                {
                    help = "Upload a new map (Include a .msav file with command message)";
                    role = reviewerRole;
                    usage = "<.msav attachment>";
                    category = mapReviewer;
                    aliases.add("ul");
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    Seq<MessageAttachment> ml = new Seq<>();
                    for (MessageAttachment ma : ctx.event.getMessageAttachments()) {
                        String[] splitMessage = ma.getFileName().split("\\.");
                        if (splitMessage[splitMessage.length - 1].trim().equals("msav")) {
                            ml.add(ma);
                        }
                    }
                    if (ml.size != 1) {
                        eb.setTitle("Map upload terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("You need to add one valid .msav file!");
                        ctx.sendMessage(eb);
                        return;
                    }
                    for (MessageAttachment ma : ml) {
                        boolean updated = false;
                        if (Core.settings.getDataDirectory().child("maps").child(ma.getFileName()).exists()) {
                            // update the map
//                            Core.settings.getDataDirectory().child("maps").child(ma.getFileName()).delete();
                            updated = true;
//                        eb.setTitle("Map upload terminated.");
//                        eb.setColor(Pals.error);
//                        eb.setDescription("There is already a map with this name on the server!");
//                        ctx.sendMessage(eb);
//                        return;
                        }
                        // more custom filename checks possible

                        CompletableFuture<byte[]> cf = ma.downloadAsByteArray();
                        Fi fh = Core.settings.getDataDirectory().child("maps").child(ma.getFileName());

                        try {
                            byte[] data = cf.get();
                            if (!SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
                                eb.setTitle("Map upload terminated.");
                                eb.setColor(Pals.error);
                                eb.setDescription("Map file `" + fh.name() + "` corrupted or invalid.");
                                ctx.sendMessage(eb);
                                continue;
                            }
                            if (updated)
                                Core.settings.getDataDirectory().child("maps").child(ma.getFileName()).delete();
                            fh.writeBytes(cf.get(), false);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        maps.reload();
                        eb.setTitle("Map upload completed.");
                        if (updated)
                            eb.setDescription("Successfully updated " + ma.getFileName());
                        else
                            eb.setDescription(ma.getFileName() + " was added successfully into the playlist!");
                        ctx.sendMessage(eb);
                        logAction((updated ? updateMap : uploadMap), null, ctx, null, ma);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("removemap") {
                {
                    help = "Remove a map from the playlist (use mapname/mapid retrieved from the %maps command)".replace("%", ioMain.prefix);
                    role = reviewerRole;
                    usage = "<mapname/mapid>";
                    category = mapReviewer;
                    minArguments = 1;
                    aliases.add("rm");
                }

                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length < 2) {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Not enough arguments, use `%removemap <mapname/mapid>`".replace("%", ioMain.prefix));
                        ctx.sendMessage(eb);
                        return;
                    }
                    Map found = getMapBySelector(ctx.message.trim());
                    if (found == null) {
                        eb.setTitle("Command terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("Map not found");
                        ctx.sendMessage(eb);
                        return;
                    }

                    maps.removeMap(found);
                    maps.reload();

                    eb.setTitle("Command executed.");
                    eb.setDescription(found.name() + " was successfully removed from the playlist.");
                    ctx.sendMessage(eb);
                }
            });
        }

        if (data.has("mapSubmissions_id")) {
            TextChannel tc = getTextChannel(ioMain.data.getString("mapSubmissions_id"));
            handler.registerCommand(new Command("submitmap") {
                {
                    help = " Submit a new map to be added into the server playlist in a .msav file format.";
                    usage = "<.msav attachment>";
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    Seq<MessageAttachment> ml = new Seq<>();
                    for (MessageAttachment ma : ctx.event.getMessageAttachments()) {
                        if (ma.getFileName().split("\\.", 2)[1].trim().equals("msav")) {
                            ml.add(ma);
                        }
                    }
                    if (ml.size != 1) {
                        eb.setTitle("Map upload terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("You need to add one valid .msav file!");
                        ctx.sendMessage(eb);
                        return;
                    } else if (Core.settings.getDataDirectory().child("maps").child(ml.get(0).getFileName()).exists()) {
                        eb.setTitle("Map upload terminated.");
                        eb.setColor(Pals.error);
                        eb.setDescription("There is already a map with this name on the server!");
                        ctx.sendMessage(eb);
                        return;
                    }
                    CompletableFuture<byte[]> cf = ml.get(0).downloadAsByteArray();

                    try {
                        byte[] data = cf.get();
                        if (!SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
                            eb.setTitle("Map upload terminated.");
                            eb.setColor(Pals.error);
                            eb.setDescription("Map file corrupted or invalid.");
                            ctx.sendMessage(eb);
                            return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    eb.setTitle("Map upload completed.");
                    eb.setDescription(ml.get(0).getFileName() + " was successfully queued for review by moderators!");

                    try {
                        InputStream data = ml.get(0).downloadAsInputStream();
                        StringMap meta = getMeta(data);
                        Fi mapFile = new Fi("./temp/map_submit_" + meta.get("name").replaceAll("[^a-zA-Z0-9\\.\\-]", "_") + ".msav");
                        mapFile.writeBytes(cf.get(), false);

                        EmbedBuilder embed = new EmbedBuilder()
                                .setTitle(escapeEverything(meta.get("name")))
                                .setDescription(meta.get("description"))
                                .setAuthor(ctx.author.getName(), ctx.author.getAvatar().getUrl().toString(), ctx.author.getAvatar().getUrl().toString())
                                .setColor(new Color(0xF8D452))
                                .setFooter("Size: " + meta.getInt("width") + " X " + meta.getInt("height"));
                        debug("mapFile size: @", mapFile.length());
                        attachMapPng(mapFile, embed, tc);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    assert tc != null;
                    ctx.sendMessage(eb);
                }
            });


        }
    }
}