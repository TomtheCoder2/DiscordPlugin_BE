package mindustry.plugin.utils;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Http;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.io.CounterInputStream;
import com.github.kevinsawicki.http.HttpRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.SaveIO;
import mindustry.io.SaveVersion;
import mindustry.maps.Map;
import mindustry.maps.Maps;
import mindustry.mod.Mods;
import mindustry.net.Administration;
import mindustry.plugin.data.PlayerData;
import mindustry.plugin.database.MapData;
import mindustry.plugin.discordcommands.Command;
import mindustry.plugin.discordcommands.Context;
import mindustry.plugin.ioMain;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import mindustry.ui.Menus;
import mindustry.world.Block;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.emoji.KnownCustomEmoji;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.message.MessageCreateEvent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.InflaterInputStream;

import static arc.util.Log.debug;
import static arc.util.Log.err;
import static mindustry.Vars.*;
import static mindustry.plugin.database.Utils.*;
import static mindustry.plugin.discordcommands.DiscordCommands.error_log_channel;
import static mindustry.plugin.ioMain.api;
import static mindustry.plugin.ioMain.contentHandler;
import static mindustry.plugin.utils.ranks.Utils.rankNames;
//import java.sql.*;

public class Utils {
    public static String url = null;
    public static String maps_url = null;
    public static String apapi_key = null;
    public static String user = null;
    public static String password = null;
    public static int chatMessageMaxSize = 256;
    public static String welcomeMessage = "";
    public static String statMessage = "";
    public static String infoMessage = "";
    public static String reqMessage = "";
    public static String rankMessage = "";
    public static String ruleMessage = "";
    public static String noPermissionMessage = "You don't have the required rank for this command. Learn more about ranks [pink]/info[]";
    // whether ip verification is in place (detect VPNs, disallow their build rights)
    public static Boolean verification = false;
    public static String promotionMessage =
            """
                    [sky]%player%, you have been promoted to [sky]<%rank%>[]!
                    [#4287f5]You reached a playtime of - %playtime% minutes!
                    [#f54263]You played a total of %games% games!
                    [#9342f5]You built a total of %buildings% buildings!
                    [sky]Enjoy your time on the [white][#ff2400]P[#ff4900]H[#ff6d00]O[#ff9200]E[#ffb600]N[#ffdb00]I[#ffff00]X [white]Servers[sky]!""";
    public static Seq<String> bannedNames = new Seq<>();
    public static Seq<String> onScreenMessages = new Seq<>();
    public static Seq<Block> bannedBlocks = new Seq<>();
    public static String eventIp = "";
    public static int eventPort = 1001;

    public static void init() {
        // initialize all rankNames
        mindustry.plugin.utils.ranks.Utils.init();

        bannedNames.add("IGGGAMES");
        bannedNames.add("CODEX");
        bannedNames.add("VALVE");
        bannedNames.add("tuttop");
        bannedNames.add("Volas Y0uKn0w1sR34Lp");
        bannedNames.add("IgruhaOrg");
        bannedNames.add("андрей");
        bannedNames.add("THIS IS MY KINGDOM CUM, THIS IS MY CUM");

        bannedBlocks.add(Blocks.conveyor);
        bannedBlocks.add(Blocks.titaniumConveyor);
        bannedBlocks.add(Blocks.junction);
        bannedBlocks.add(Blocks.router);

        statMessage = Core.settings.getString("statMessage");
        reqMessage = Core.settings.getString("reqMessage");
        rankMessage = Core.settings.getString("rankMessage");
        welcomeMessage = Core.settings.getString("welcomeMessage");
        ruleMessage = Core.settings.getString("ruleMessage");
        infoMessage = Core.settings.getString("infoMessage");
    }

    // region string modifiers

    /**
     * replace all color codes, ` and @
     *
     * @param string the original string
     */
    public static String escapeCharacters(String string) {
        return escapeColorCodes(string.replaceAll("`", "").replaceAll("@", ""));
    }

    /**
     * Remove all color codes
     *
     * @param string the input string (for example a name or a message)
     */
    public static String escapeColorCodes(String string) {
        return Strings.stripColors(string);
    }

    /**
     * remove everything (rank symbol colors etc.)
     *
     * @param string the player name (in most cases)
     */
    public static String escapeEverything(String string) {
        return escapeColorCodes(string
//                .replaceAll(" ", "")
                        .replaceAll("\\|(.)\\|", "")
                        .replaceAll("\\[accent\\]", "")
                        .replaceAll("\\|(.)\\|", "")
        ).replaceAll("\\|(.)\\|", "");
    }

    /**
     * check if a string is an ip
     *
     * @implNote ik there are functions for that, but I like to do it with regex
     */
    public static boolean isValidIPAddress(String ip) {

        // Regex for digit from 0 to 255.
        String zeroTo255
                = "(\\d{1,2}|(0|1)\\"
                + "d{2}|2[0-4]\\d|25[0-5])";

        // Regex for a digit from 0 to 255 and
        // followed by a dot, repeat 4 times.
        // this is the regex to validate an IP address.
        String regex
                = zeroTo255 + "\\."
                + zeroTo255 + "\\."
                + zeroTo255 + "\\."
                + zeroTo255;

        // Compile the ReGex
        Pattern p = Pattern.compile(regex);

        // If the IP address is empty
        // return false
        if (ip == null) {
            return false;
        }

        // Pattern class contains matcher() method
        // to find matching between given IP address
        // and regular expression.
        Matcher m = p.matcher(ip);

        // Return if the IP address
        // matched the ReGex
        return m.matches();
    }


    /**
     * remove everything (rank symbol colors etc.)
     *
     * @param player the player
     */
    public static String escapeEverything(Player player) {
        return escapeEverything(player.name);
    }

    /**
     * Replace %player% with player name, %playtime% with play time etc
     *
     * @param message the message to replace
     * @param player  for the stats
     */
    public static String formatMessage(Player player, String message) {
        try {
            message = message.replaceAll("%player%", escapeCharacters(player.name));
            message = message.replaceAll("%map%", state.map.name());
            message = message.replaceAll("%wave%", String.valueOf(state.wave));
            PlayerData pd = getData(player.uuid());
            if (pd != null) {
                message = message.replaceAll("%playtime%", String.valueOf(pd.playTime));
                message = message.replaceAll("%games%", String.valueOf(pd.gamesPlayed));
                message = message.replaceAll("%buildings%", String.valueOf(pd.buildingsBuilt));
                message = message.replaceAll("%rank%", rankNames.get(pd.rank).tag + " " + escapeColorCodes(rankNames.get(pd.rank).name));
//                if(pd.discordLink.length() > 0){
//                    User discordUser = api.getUserById(pd.discordLink).get(2, TimeUnit.SECONDS);
//                    if(discordUser != null) {
//                        message = message.replaceAll("%discord%", discordUser.getDiscriminatedName());
//                    }
//                } else{
                message = message.replaceAll("%discord%", "unlinked");
//                }
            }
        } catch (Exception e) {
            System.out.println(e);
        }
        ;
        return message;
    }


    // region getters

    /**
     * Get a map by name
     *
     * @param query the map name
     */
    public static Map getMapBySelector(String query) {
        Map found = null;
        try {
            // try by number
            found = maps.customMaps().get(Integer.parseInt(query));
        } catch (Exception e) {
            // try by name
            for (Map m : maps.customMaps()) {
                if (m.name().replaceAll(" ", "").toLowerCase().contains(query.toLowerCase().replaceAll(" ", ""))) {
                    found = m;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Find a player by name
     *
     * @param identifier the name, id, uuid, con or address
     */
    public static Player findPlayer(String identifier) {
        Player found = null;
        for (Player player : Groups.player) {
            if (player == null) return null; // how does that even happen wtf
            if (player.uuid() == null) return null;
            if (player.con == null) return null;
            if (player.con.address == null) return null;

            if (player.con.address.equals(identifier.replaceAll(" ", "")) ||
                    String.valueOf(player.id).equals(identifier.replaceAll(" ", "")) ||
                    player.uuid().equals(identifier.replaceAll(" ", "")) ||
                    escapeEverything(player).toLowerCase().replaceAll(" ", "").startsWith(identifier.toLowerCase().replaceAll(" ", ""))) {
                found = player;
            }
        }
        return found;
    }

    /**
     * get player info by uuid or name
     */
    public static Administration.PlayerInfo getPlayerInfo(String target) {
        Administration.PlayerInfo info;
        Player player = findPlayer(target);
        if (player != null) {
            info = netServer.admins.getInfo(player.uuid());
            System.out.println("Found " + player.name);
        } else {
            info = netServer.admins.getInfoOptional(target);
        }
        return info;
    }

    /**
     * Get mod by name
     */
    public static Mods.LoadedMod getMod(String name) {
        return mods.list().find(p -> escapeColorCodes(p.meta.name).equalsIgnoreCase(name) || escapeColorCodes(p.meta.displayName).equalsIgnoreCase(name));
    }

    @Deprecated
    public static boolean isInt(String str) {
        try {
            @SuppressWarnings("unused")
            int x = Integer.parseInt(str);
            return true; //String is an Integer
        } catch (NumberFormatException e) {
            return false; //String is not an Integer
        }

    }

    /**
     * Change the current map
     *
     * @param found map
     */
    public static void changeMap(Map found) {
        Class<Maps> mapsClass = Maps.class;
        Field mapsField;
        try {
            mapsField = mapsClass.getDeclaredField("maps");
        } catch (NoSuchFieldException ex) {
            throw new RuntimeException("Could not find field 'maps' of class 'mindustry.maps.Maps'");
        }
        mapsField.setAccessible(true);
        Field mapsListField = mapsField;

        Seq<Map> mapsList;
        try {
            mapsList = (Seq<Map>) mapsListField.get(maps);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("unreachable");
        }

        Seq<Map> tempMapsList = mapsList.removeAll(map -> !map.custom || map != found);

        try {
            mapsListField.set(maps, tempMapsList);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("unreachable");
        }

        Events.fire(new EventType.GameOverEvent(Team.crux));

        try {
            mapsListField.set(maps, mapsList);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("unreachable");
        }
        maps.reload();
    }

//    public static void changeToMap(Map targetMap) {
//        Core.app.getListeners().each(lst -> {
//            if (lst instanceof ServerControl) {
//                ServerControl scont = (ServerControl) lst;
//                Reflect.set(scont, "nextMapOverride", targetMap);
//                Events.fire(new EventType.GameOverEvent(Team.crux));
//                return;
//            }
//        });
//    }


    // region discord

    /**
     * Send a schematic to channel of the ctx
     */
    public static void sendSchem(Schematic schem, Context ctx) {
        ItemSeq req = schem.requirements();
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x00ffff))
                .setAuthor(ctx.author)
                .setTitle(schem.name());
        StringBuilder sb = new StringBuilder("");
        for (ItemStack item : req) {
            Collection<KnownCustomEmoji> emojis = api.getCustomEmojisByNameIgnoreCase(item.item.name.replaceAll("-", ""));
//            eb.addField(emoijs.iterator().next().getMentionTag(), String.valueOf(item.amount), true);
            sb.append(emojis.iterator().next().getMentionTag()).append(item.amount).append("    ");
        }
        eb.setDescription(schem.description());
        eb.addField("**Requirements:**", sb.toString());
        // power emojis
        String powerPos = api.getCustomEmojisByNameIgnoreCase("power_pos").iterator().next().getMentionTag();
        String powerNeg = api.getCustomEmojisByNameIgnoreCase("power_neg").iterator().next().getMentionTag();
        eb.addField("**Power:**", powerPos + "+" + schem.powerProduction() + "    " +
                powerNeg + "-" + schem.powerConsumption() + "     \n" +
                powerPos + "-" + powerNeg + (schem.powerProduction() - schem.powerConsumption()));

        // preview schem
        BufferedImage visualSchem;
        File imageFile;
        Fi schemFile;
        try {
            visualSchem = contentHandler.previewSchematic(schem);
            imageFile = new File("temp/" + "image_" + schem.name().replaceAll("[^a-zA-Z0-9\\.\\-]", "_") + ".png");
            ImageIO.write(visualSchem, "png", imageFile);
            // crate the .msch file
            schemFile = new Fi("temp/" + "file_" + schem.name().replaceAll("[^a-zA-Z0-9\\.\\-]", "_") + ".msch");
            Schematics.write(schem, schemFile);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        eb.setImage("attachment://" + imageFile.getName());
        MessageBuilder mb = new MessageBuilder();
        mb.addEmbed(eb);
        mb.addFile(imageFile);
        mb.addAttachment(schemFile.file());
        mb.send(ctx.channel);
    }


    /**
     * check if the message starts with the schematic prefix
     */
    public static boolean checkIfSchem(MessageCreateEvent event) {
        // check if it's a schem encoded in base64
        String message = event.getMessageContent();
        if (event.getMessageContent().startsWith("bXNjaA")) {
            try {
                debug("send schem");
                sendSchem(contentHandler.parseSchematic(message), new Context(event, null, null));
                event.deleteMessage();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // ========= check if it's a schem file ============
        // download all files
        Seq<MessageAttachment> ml = new Seq<>();
        Seq<MessageAttachment> txtData = new Seq<>();
        for (MessageAttachment ma : event.getMessageAttachments()) {
            if ((ma.getFileName().split("\\.", 2)[1].trim().equals("msch")) && !event.getMessageAuthor().isBotUser()) { // check if its a .msch file
                ml.add(ma);
            }
            if ((ma.getFileName().split("\\.", 2)[1].trim().equals("txt")) && !event.getMessageAuthor().isBotUser()) { // check if its a .msch file
                txtData.add(ma);
            }
        }

        if (ml.size > 0) {
            CompletableFuture<byte[]> cf = ml.get(0).downloadAsByteArray();
            try {
                byte[] data = cf.get();
                Schematic schem = Schematics.read(new ByteArrayInputStream(data));
                sendSchem(schem, new Context(event, null, null));
                return true;
            } catch (Exception e) {
                assert error_log_channel != null;
                error_log_channel.sendMessage(new EmbedBuilder().setTitle(e.getMessage()).setColor(new Color(0xff0000)));
                e.printStackTrace();
            }
        }

        if (txtData.size > 0) {
            CompletableFuture<byte[]> cf = txtData.get(0).downloadAsByteArray();
            try {
                byte[] data = cf.get();
                String base64Encoded = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data)))
                        .lines().parallel().collect(Collectors.joining("\n"));
                Schematic schem = contentHandler.parseSchematic(base64Encoded);
                sendSchem(schem, new Context(event, null, null));
                event.deleteMessage();
                return true;
            } catch (Exception e) {
                assert error_log_channel != null;
                error_log_channel.sendMessage(new EmbedBuilder().setTitle(e.getMessage()).setColor(new Color(0xff0000)));
                e.printStackTrace();
            }
        }
        return false;
    }


    /**
     * Convert a long to formatted time.
     *
     * @param epoch the time in long.
     * @return formatted time
     */
    public static String epochToString(long epoch) {
        Date date = new Date(epoch * 1000L);
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        return format.format(date) + " UTC";
    }

    /**
     * if there are too few arguments for the command
     */
    public static void tooFewArguments(Context ctx, Command command) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Too few arguments!")
                .setDescription("Usage: " + ioMain.prefix + command.name + " " + command.usage)
                .setColor(Pals.error);
        ctx.sendMessage(eb);
    }

    /**
     * send the player not found message for discord commands
     */
    public static void playerNotFound(String name, EmbedBuilder eb, Context ctx) {
        eb.setTitle("Command terminated");
        eb.setDescription("Player `" + escapeEverything(name) + "` not found.");
        eb.setColor(Pals.error);
        ctx.sendMessage(eb);
    }

    public static String hsvToRgb(double hue, float saturation, float value) {

        int h = (int) (hue * 6);
        float f = (float) (hue * 6 - h);
        float p = value * (1 - saturation);
        float q = value * (1 - f * saturation);
        float t = value * (1 - (1 - f) * saturation);

        return switch (h) {
            case 0 -> rgbToString(value, t, p);
            case 1 -> rgbToString(q, value, p);
            case 2 -> rgbToString(p, value, t);
            case 3 -> rgbToString(p, q, value);
            case 4 -> rgbToString(t, p, value);
            case 5 -> rgbToString(value, p, q);
            default -> throw new RuntimeException("Something went wrong when converting from HSV to RGB. Input was " + hue + ", " + saturation + ", " + value);
        };
    }

    /**
     * Get a png from map (InputStream)
     *
     * @param imageFileName the name of the image where you want to save it
     */
    public static void mapToPng(InputStream stream, String imageFileName) throws IOException {
        debug("start function mapToPng");
        Http.post(maps_url + "/map").content(stream, stream.available()).block(res -> {
            debug(res.getStatus());
            debug("received data (mapToPng)");
            var pix = new Pixmap(res.getResultAsStream().readAllBytes());
            PixmapIO.writePng(new Fi("temp/" + "image_" + imageFileName), pix); // Write to a file
            debug("image height: @", pix.height);
            debug("image width: @", pix.height);
        });
    }

    /**
     * Send a map to the mindServ to get a png
     *
     * @param map        map File
     * @param outputFile where the png should be saved
     * @return whether it was successfully
     */
    public static boolean getMapImage(File map, File outputFile) {
        try {
            HttpRequest req = HttpRequest.post(maps_url + "/map");
            req.contentType("application/octet-stream");
            req.send(map);

            if (req.ok()) {
                req.receive(outputFile);
                return true;
            }
            Log.warn("@:@", req.code(), req.body());
            return false;
        } catch (HttpRequest.HttpRequestException e) {
            Log.err("Content Server is not running.\n", e);
            return false;
        } catch (Exception e) {
            Log.err(e);
            return false;
        }
    }

    public static void attachMapPng(Map found, EmbedBuilder embed, Context ctx) throws IOException {
        Fi mapFile = found.file;
        attachMapPng(mapFile, embed, ctx);
    }

    public static void attachMapPng(Fi mapFile, EmbedBuilder embed, Context ctx) throws IOException {
        attachMapPng(mapFile, embed, ctx.channel);
    }

    public static void attachMapPng(Fi mapFile, EmbedBuilder embed, TextChannel channel) throws IOException {
        InputStream stream = mapFile.readByteStream();
        String imageFileName = mapFile.name().replaceAll("[^a-zA-Z0-9\\.\\-]", "_").replaceAll(".msav", ".png");
        Log.debug("File size of map: @", stream.readAllBytes().length);
        File imageFile = new File("temp/" + "image_" + imageFileName);
        MessageBuilder mb = new MessageBuilder();
        if (getMapImage(mapFile.file(), imageFile)) {
            debug("received image!");
            embed.setImage("attachment://" + "image_" + imageFileName);
            mb.addEmbed(embed);
            mb.addAttachment(imageFile);
            mb.addAttachment(mapFile.file());
            mb.send(channel).join();
            imageFile.delete();
        } else {
            embed.setFooter("Content Server is not running.");
            mb.addEmbed(embed);
            mb.addAttachment(mapFile.file());
            mb.send(channel).join();
        }
    }

    /**
     * get metadata from a map saved as a file converted to an inputStream
     */
    public static StringMap getMeta(InputStream is) throws IOException {
        InputStream ifs = new InflaterInputStream(is);
        CounterInputStream counter = new CounterInputStream(ifs);
        DataInputStream stream = new DataInputStream(counter);

        SaveIO.readHeader(stream);
        int version = stream.readInt();
        SaveVersion ver = SaveIO.getSaveWriter(version);
        StringMap[] metaOut = {null};
        ver.region("meta", stream, counter, in -> metaOut[0] = ver.readStringMap(in));

        return metaOut[0];
    }

    public static String rgbToString(float r, float g, float b) {
        String rs = Integer.toHexString((int) (r * 256));
        String gs = Integer.toHexString((int) (g * 256));
        String bs = Integer.toHexString((int) (b * 256));
        return rs + gs + bs;
    }

    /**
     * copy a file to another
     */
    public static void copy(String path, Fi to) {
        try {
            final InputStream in = Utils.class.getClassLoader().getResourceAsStream(path);
            final OutputStream out = to.write();

            int data;
            if (in == null) {
                err("Could not find resource: " + path);
                return;
            }
            while ((data = in.read()) != -1) {
                out.write(data);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    // old function to get info from the database
//    public static PlayerData getData(String uuid) {
//        try(Jedis jedis = ioMain.pool.getResource()) {
//            String json = jedis.get(uuid);
//            if(json == null) return null;
//
//            try {
//                return gson.fromJson(json, PlayerData.class);
//            } catch(Exception e){
//                e.printStackTrace();
//                return null;
//            }
//        }
//    }
//
//    public static void setData(String uuid, PlayerData pd) {
//        try(Jedis jedis = ioMain.pool.getResource()) {
//            try {
//                String json = gson.toJson(pd);
//                jedis.set(uuid, json);
//            } catch(Exception e){
//                e.printStackTrace();
//            }
//        }
//    }

    /**
     * get a channel by id
     */
    public static TextChannel getTextChannel(String id) {
        Optional<Channel> dc = api.getChannelById(id);
        if (dc.isEmpty()) {
            err("[ERR!] discordplugin: channel not found! " + id);
            return null;
        }
        Optional<TextChannel> dtc = dc.get().asTextChannel();
        if (dtc.isEmpty()) {
            err("[ERR!] discordplugin: textchannel not found! " + id);
            return null;
        }
        return dtc.get();
    }

    /**
     * Converts a {@link JsonObject} to {@link EmbedBuilder}.
     * Supported Fields: Title, Author, Description, Color, Fields, Thumbnail, Footer.
     *
     * @param json The JsonObject
     * @return The Embed
     */
    public static EmbedBuilder jsonToEmbed(JsonObject json) {
        EmbedBuilder embedBuilder = new EmbedBuilder();

        JsonPrimitive titleObj = json.getAsJsonPrimitive("title");
        if (titleObj != null) { // Make sure the object is not null before adding it onto the embed.
            embedBuilder.setTitle(titleObj.getAsString());
        }

        JsonObject authorObj = json.getAsJsonObject("author");
        if (authorObj != null) {
            String authorName = authorObj.get("name").getAsString();
            String authorIconUrl = authorObj.get("icon_url").getAsString();
            String authorUrl = null;
            if (authorObj.get("url") != null)
                authorUrl = authorObj.get("url").getAsString();
            if (authorIconUrl != null) // Make sure the icon_url is not null before adding it onto the embed. If its null then add just the author's name.
                embedBuilder.setAuthor(authorName, (authorUrl != null ? authorUrl : "https://www.youtube.com/watch?v=iik25wqIuFo"), authorIconUrl); // default: little rick roll
            else
                embedBuilder.setAuthor(authorName);
        }

        JsonPrimitive descObj = json.getAsJsonPrimitive("description");
        if (descObj != null) {
            embedBuilder.setDescription(descObj.getAsString());
        }

        JsonPrimitive colorObj = json.getAsJsonPrimitive("color");
        if (colorObj != null) {
            Color color = new Color(colorObj.getAsInt());
            embedBuilder.setColor(color);
        }

        JsonObject imageObj = json.getAsJsonObject("image");
        if (imageObj != null) {
            embedBuilder.setImage(imageObj.get("url").getAsString());
        }

        JsonArray fieldsArray = json.getAsJsonArray("fields");
        if (fieldsArray != null) {
            // Loop over the fields array and add each one by order to the embed.
            fieldsArray.forEach(ele -> {
                debug(ele);
                if (ele != null && !ele.isJsonNull()) {
                    String name = ele.getAsJsonObject().get("name").getAsString();
                    String content = ele.getAsJsonObject().get("value").getAsString();
                    boolean inline = ele.getAsJsonObject().get("inline").getAsBoolean();
                    embedBuilder.addField(name, content, inline);
                }
            });
        }

        JsonObject thumbnailObj = json.getAsJsonObject("thumbnail");
        if (thumbnailObj != null) {
            embedBuilder.setThumbnail(thumbnailObj.get("url").getAsString());
        }

        JsonPrimitive timeStampObj = json.getAsJsonPrimitive("timestamp");
        if (timeStampObj != null) {
            if (timeStampObj.getAsBoolean()) {
                embedBuilder.setTimestampToNow();
            }
        }

        JsonObject footerObj = json.getAsJsonObject("footer");
        if (footerObj != null) {
            String content = footerObj.get("text").getAsString();
            String footerIconUrl = footerObj.get("icon_url").getAsString();

            if (footerIconUrl != null)
                embedBuilder.setFooter(content, footerIconUrl);
            else
                embedBuilder.setFooter(content);
        }

        return embedBuilder;
    }


    // copied and pasted from the internet, hope it works
    public static boolean onlyDigits(String str) {
        // Regex to check string
        // contains only digits
        String regex = "[0-9]+";

        // Compile the ReGex
        Pattern p = Pattern.compile(regex);

        // If the string is empty
        // return false
        if (str == null) {
            return false;
        }

        // Find match between given string
        // and regular expression
        // using Pattern.matcher()
        Matcher m = p.matcher(str);

        // Return if the string
        // matched the ReGex
        return m.matches();
    }

    /**
     * WIP
     */
    public static void execute(String command) throws IOException {
        Process process = Runtime.getRuntime().exec(command);
//        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//        String line = "";
////        StringBuilder output = new StringBuilder();
//        while ((line = reader.readLine()) != null) {
//            System.out.println(line);
////            output.append(line);
//        }
    }

    /**
     * WIP
     */
    public static void restartApplication() throws IOException, URISyntaxException {
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final File currentJar = new File(Core.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        /* is it a jar file? */
        if (!currentJar.getName().endsWith(".jar"))
            return;

        /* Build command: java -jar application.jar */
        final ArrayList<String> command = new ArrayList<String>();
//        command.add("screen -d -r v7 -X stuff $'");
//        command.add("sleep");
//        command.add("0\n");
//        command.add("screen");
//        command.add("-d");
//        command.add("-r");
//        command.add("v7");
//        command.add("-X");
//        command.add("stuff");
//        command.add("java -jar server-release.jar\n");
        command.add(javaBin);
        command.add("-jar");
        command.add(currentJar.getPath());
//        command.add("'");

        final ProcessBuilder builder = new ProcessBuilder(command);
        System.out.println(builder.command());
        builder.start();
        System.exit(0);
    }

    public static StringBuilder lookup(EmbedBuilder eb, Administration.PlayerInfo info) {
        eb.addField("Times kicked", String.valueOf(info.timesKicked));
        StringBuilder s = new StringBuilder();
        PlayerData pd = getData(info.id);
        if (pd != null) {
            eb.addField("Rank", rankNames.get(pd.rank).name, true);
            eb.addField("Playtime", pd.playTime + " minutes", true);
            eb.addField("Games", String.valueOf(pd.gamesPlayed), true);
            eb.addField("Buildings built", String.valueOf(pd.buildingsBuilt), true);
            eb.addField("Banned", String.valueOf(pd.banned), true);
            if (pd.banned || pd.bannedUntil > Instant.now().getEpochSecond()) {
                eb.addField("Ban Reason", escapeEverything(pd.banReason), true);
                long now = Instant.now().getEpochSecond();
                // convert seconds to days hours seconds etc
                int n = (int) (pd.bannedUntil - now);
                int day = n / (24 * 3600);

                n = n % (24 * 3600);
                int hour = n / 3600;

                n %= 3600;
                int minutes = n / 60;

                n %= 60;
                int seconds = n;


                eb.addField("Remaining ban time", day + " " + "days " + hour
                        + " " + "hours " + minutes + " "
                        + "minutes " + seconds + " "
                        + "seconds ", true);
                eb.addField("Banned Until", new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new java.util.Date(pd.bannedUntil * 1000)), true);
            }

//            CompletableFuture<User> user = ioMain.api.getUserById(pd.discordLink);
//            user.thenAccept(user1 -> {
//                eb.addField("Discord Link", user1.getDiscriminatedName());
//            });


        }
        s.append("**All used names: **\n");
        for (String name : info.names) {
            s.append(escapeEverything(name.replaceAll(" ", "")).replaceAll("<.*?>", "").replaceAll("\\[.*?\\]", "")).append(" / ");
        }
        s.append("**\n\nCurrent Name with color codes: **\n");
        s.append(info.lastName);
        return s;
    }

    /**
     * create a rate menu for all players
     */
    public static void rateMenu() {
        String mapName = state.map.name();
        int id = Menus.registerMenu((player, selection) -> {
            if (selection == 0) {
                ratePositive(mapName, player);
            } else if (selection == 1) {
                rateNegative(mapName, player);
            }
        });
        Call.menu(id,
                "Rate this map! [pink]" + mapName,
                "Do you like this map? Vote [green]yes [white]or [scarlet]no:",
                new String[][]{
                        new String[]{"[green]Yes", "[scarlet]No"},
                        new String[]{"Close"}
                }
        );
    }

    /**
     * Create a menu to rate the current map for a player
     */
    public static void rateMenu(Player p) {
        String mapName = state.map.name();
        int id = Menus.registerMenu((player, selection) -> {
            if (selection == 0) {
                ratePositive(mapName, player);
            } else if (selection == 1) {
                rateNegative(mapName, player);
            }
        });
        Call.menu(p.con, id,
                "Rate this map! [pink]" + mapName,
                "Do you like this map? Vote [green]yes [white]or [scarlet]no:",
                new String[][]{
                        new String[]{"[green]Yes", "[scarlet]No"},
                        new String[]{"Close"}
                }
        );
    }

    /**
     * Rate a map positive
     */
    public static void rateNegative(String mapName, Player player) {
        MapData voteMapData = getMapData(mapName);
        if (voteMapData != null) {
            voteMapData.negativeRating++;
        } else {
            voteMapData = new MapData(mapName);
            voteMapData.negativeRating = 1;
        }
        rateMap(mapName, voteMapData);
        player.sendMessage("Successfully gave a [red]negative [white]feedback for " + mapName + "[white]!");
    }

    /**
     * Rate a map positive
     */
    public static void ratePositive(String mapName, Player player) {
        MapData voteMapData = getMapData(mapName);
        if (voteMapData != null) {
            voteMapData.positiveRating++;
        } else {
            voteMapData = new MapData(mapName);
            voteMapData.positiveRating = 1;
        }
        rateMap(mapName, voteMapData);
        player.sendMessage("Successfully gave a [green]positive [white]feedback for " + mapName + "[white]!");
    }

    /**
     * Send message without response handling
     *
     * @param user    User to dm
     * @param content message
     */
    public void sendMessage(User user, String content) {
        user.openPrivateChannel().join().sendMessage(content);
    }


//        public static getCore(Team team){
//        Tile[][] tiles = world.getTiles();
//        for (int x = 0; x < tiles.length; ++x) {
//            for(int y = 0; y < tiles[0].length; ++y) {
//                if (tiles[x][y] != null && tiles[x][y].entity != null) {
//                    TileEntity ent = tiles[x][y].ent();
//                    if (ent instanceof CoreBlock.CoreEntity) {
//                        if(ent.getTeam() == team){
//                            return (CoreBlock.CoreBuild) ent;
//                        }
//                    }
//                }
//            }
//        }
//        return null;
//    }

    // colors for errors, info, warning etc. messages
    public static class Pals {
        public static Color warning = (Color.getHSBColor(5, 85, 95));
        public static Color info = (Color.getHSBColor(45, 85, 95));
        public static Color error = (Color.getHSBColor(3, 78, 91));
        public static Color success = (Color.getHSBColor(108, 80, 100));
    }

    public static class Categories {
        public static final String moderation = "Moderation";
        public static final String management = "Management";
        public static final String mapReviewer = "Map Reviewer";
    }

    public static class uMap {
        public String name, author, description;
        public ObjectMap<String, String> tags = new ObjectMap<>();
        public BufferedImage image;
        public BufferedImage terrain;
    }
}