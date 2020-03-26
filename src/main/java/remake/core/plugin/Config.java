package remake.core.plugin;

import org.hjson.JsonArray;
import org.hjson.JsonObject;
import org.hjson.JsonValue;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static remake.Main.root;
import static remake.Vars.config_version;

public class Config {
    public final int version;
    public final Locale language;
    public final boolean serverenable;
    public final int serverport;
    public final boolean clientenable;
    public final int clientport;
    public final String clienthost;
    public final boolean realname;
    public final boolean strictname;
    public final int cupdatei;
    public final boolean detectreactor;
    public final boolean scanresource;
    public final boolean antigrief;
    public final boolean alertaction;
    public final boolean explimit;
    public final double basexp;
    public final double exponent;
    public final boolean levelupalarm;
    public final int alarmlevel;
    public final boolean banshare;
    public final JsonArray bantrust;
    public final boolean query;
    public final boolean antivpn;
    public final boolean antirush;
    public final LocalTime antirushtime;
    public final boolean voteenable;
    public final boolean logging;
    public final boolean update;
    public final boolean internalDB;
    public final boolean DBServer;
    public final String DBurl;
    public final boolean OldDBMigration;
    public final String OldDBurl;
    public final String OldDBID;
    public final String OldDBPW;
    public final String dataserverurl;
    public final String dataserverid;
    public final String dataserverpw;
    public final boolean loginenable;
    public final String passwordmethod;
    public final boolean validconnect;
    public final String emailserver;
    public final int emailport;
    public final String emailAccountID;
    public final String emailUsername;
    public final String emailPassword;
    public final String discordtoken;
    public final Float discordguild;
    public final String discordroom;
    public final String discordlink;
    public final String discordrole;
    public final String discordprefix;
    public final boolean translate;
    public final String translateid;
    public final String translatepw;
    public final boolean debug;
    public final String debugcode;
    public final boolean crashreport;
    public final LocalTime savetime;
    public final boolean rollback;
    public final int slotnumber;
    public final boolean autodifficulty;
    public final int difficultyEasy;
    public final int difficultyNormal;
    public final int difficultyHard;
    public final int difficultyInsane;
    public final boolean border;
    public final int spawnlimit;
    public final String prefix;
    public final String eventport;

    public Config() {
        JsonObject obj = JsonValue.readHjson(root.child("config.hjson").readString()).asObject();
        version = obj.getInt("version", config_version);
        language = new Locale(obj.getString("language", System.getProperty("user.language") + "_" + System.getProperty("user.country")));
        serverenable = obj.getBoolean("serverenable", false);
        serverport = obj.getInt("serverport", 25000);
        clientenable = obj.getBoolean("clientenable", false);
        clientport = obj.getInt("clientport", 25000);
        clienthost = obj.getString("clienthost", "mindustry.kr");
        realname = obj.getBoolean("realname", false);
        strictname = obj.getBoolean("strictname", false);
        cupdatei = obj.getInt("cupdatei", 1000);
        detectreactor = obj.getBoolean("detectreactor", false);
        scanresource = obj.getBoolean("scanresource", false);
        antigrief = obj.getBoolean("antigrief", false);
        alertaction = obj.getBoolean("alertaction", false);
        explimit = obj.getBoolean("explimit", false);
        basexp = obj.getDouble("basexp", 500.0);
        exponent = obj.getDouble("exponent", 1.12);
        levelupalarm = obj.getBoolean("levelupalarm", false);
        alarmlevel = obj.getInt("alarmlevel", 20);
        banshare = obj.getBoolean("banshare", false);
        bantrust = JsonValue.readJSON(obj.getString("bantrust", "[\"127.0.0.1\"]")).asArray();
        query = obj.getBoolean("query", false);
        antivpn = obj.getBoolean("antivpn", false);
        antirush = obj.getBoolean("antirush", false);
        antirushtime = LocalTime.parse(obj.getString("antirushtime", "600"), DateTimeFormatter.ofPattern("ss"));
        voteenable = obj.getBoolean("voteenable", true);
        logging = obj.getBoolean("logging", true);
        update = obj.getBoolean("update", true);
        internalDB = obj.getBoolean("internalDB", true);
        DBServer = obj.getBoolean("DBServer", false);
        DBurl = obj.getString("DBurl", "jdbc:h2:file:./config/mods/Essentials/data/player");
        OldDBMigration = obj.getBoolean("OldDBMigration", false);
        OldDBurl = obj.getString("OldDBurl", "jdbc:sqlite:config/mods/Essentials/data/player.sqlite3");
        OldDBID = obj.getString("OldDBID", "none");
        OldDBPW = obj.getString("OldDBPW", "none");
        dataserverurl = obj.getString("dataserverurl", "none");
        dataserverid = obj.getString("dataserverid", "none");
        dataserverpw = obj.getString("dataserverpw", "none");
        loginenable = obj.getBoolean("loginenable", false);
        passwordmethod = obj.getString("passwordmethod", "password");
        validconnect = obj.getBoolean("validconnect", false);
        emailserver = obj.getString("emailserver", "smtp.gmail.com");
        emailport = obj.getInt("emailport", 587);
        emailAccountID = obj.getString("emailAccountID", "none");
        emailUsername = obj.getString("emailUsername", "none");
        emailPassword = obj.getString("emailPassword", "none");
        discordtoken = obj.getString("discordtoken", "none");
        discordguild = obj.getFloat("discordguild", 0L);
        discordroom = obj.getString("discordroom", "none");
        discordlink = obj.getString("discordlink", "none");
        discordrole = obj.getString("discordrole", "none");
        discordprefix = obj.getString("discordprefix", "none");
        translate = obj.getBoolean("translate", false);
        translateid = obj.getString("translateid", "none");
        translatepw = obj.getString("translatepw", "none");
        debug = obj.getBoolean("debug", false);
        debugcode = obj.getString("debugcode", "none");
        crashreport = obj.getBoolean("crashreport", true);
        savetime = LocalTime.parse(obj.getString("savetime", "10"), DateTimeFormatter.ofPattern("mm"));
        rollback = obj.getBoolean("rollback", false);
        slotnumber = obj.getInt("slotnumber", 1000);
        autodifficulty = obj.getBoolean("autodifficulty", false);
        difficultyEasy = obj.getInt("difficultyEasy", 2);
        difficultyNormal = obj.getInt("difficultyNormal", 4);
        difficultyHard = obj.getInt("difficultyHard", 6);
        difficultyInsane = obj.getInt("difficultyInsane", 10);
        border = obj.getBoolean("border", false);
        spawnlimit = obj.getInt("spawnlimit", 500);
        prefix = obj.getString("prefix", "[green][Essentials] []");
        eventport = obj.getString("eventport", "8000-8050");
    }
}
