package essentials;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.Array;
import arc.util.CommandHandler;
import arc.util.Strings;
import arc.util.Time;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import essentials.Threads.login;
import essentials.Threads.*;
import essentials.core.Discord;
import essentials.core.Log;
import essentials.core.PlayerDB;
import essentials.net.Client;
import essentials.net.Client.Request;
import essentials.net.Server;
import essentials.special.DataMigration;
import essentials.special.DriverLoader;
import essentials.special.IpAddressMatcher;
import essentials.special.StringUtils;
import essentials.utils.Config;
import essentials.utils.Permission;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Mechs;
import mindustry.content.UnitTypes;
import mindustry.core.Version;
import mindustry.entities.type.BaseUnit;
import mindustry.entities.type.Player;
import mindustry.entities.type.Unit;
import mindustry.game.Difficulty;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Playerc;
import mindustry.io.SaveIO;
import mindustry.mod.Plugin;
import mindustry.net.Administration.PlayerInfo;
import mindustry.net.Packets;
import mindustry.type.Mech;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.power.NuclearReactor;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.hjson.JsonObject;
import org.hjson.JsonValue;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static arc.util.Log.err;
import static arc.util.Log.info;
import static essentials.Global.*;
import static essentials.PluginData.*;
import static essentials.Threads.*;
import static essentials.core.Discord.jda;
import static essentials.core.Log.writeLog;
import static essentials.core.PlayerDB.*;
import static java.lang.Thread.sleep;
import static mindustry.Vars.*;
import static mindustry.core.NetClient.colorizeName;
import static mindustry.core.NetClient.onSetRules;

public class Main extends Plugin {
    // Classes
    public Discord discord = new Discord();
    public PlayerDB playerDB = new PlayerDB();
    public Config config = new Config();
    public Client client = new Client();
    public Permission perm = new Permission();
    public PluginData pluginData = new PluginData();
    public Threads threads = new Threads();

    public ApplicationListener listener;

    public Timer timer = new Timer(true);
    public Array<mindustry.maps.Map> maplist = Vars.maps.all();
    public Array<Playerc> players = new Array<>();

    public static Fi root = Core.settings.getDataDirectory().child("mods/Essentials/");

    public Main() {
        // 서버 버전 확인
        if(Version.build != 104){
            try {
                InputStream reader = getClass().getResourceAsStream("/plugin.json");
                BufferedReader br = new BufferedReader(new InputStreamReader(reader));
                plugin_version = JsonObject.readJSON(br).asObject().get("version").asString();
                arc.util.Log.err("Essentials "+plugin_version+" plugin only works with mindustry build 104.");
                System.exit(0);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        // 플러그인 데이터 불러오기
        pluginData = new PluginData();

        // 플러그인 설정 파일 불러오기
        config = new Config();
        config.main();

        // 예전 데이터 변환
        new DataMigration().ConvertFile();

        // DB 드라이버 다운로드
        new DriverLoader();

        // DB 연결
        playerDB = new PlayerDB();

        // 클라이언트 연결 확인
        client = new Client();
        if (config.isClientenable()) {
            log(LogType.client, "server-connecting");
            client.wakeup();
        }

        // 모든 플레이어 연결 상태를 0으로 설정
        try {
            if (config.PluginConfig.getBoolean("unexception", false)) {
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT uuid,lastdate FROM players");
                while (rs.next()) {
                    if (isLoginold(rs.getString("lastdate"))) {
                        writeData("UPDATE players SET connected = ?, connserver = ? WHERE uuid = ?", false, "none", rs.getString("uuid"));
                    }
                }
            } else {
                config.PluginConfig.set("unexception", true);
            }
        } catch (Exception e) {
            printError(e);
        }

        // 메세지 블럭에 의한 클라이언트 플레이어 카운트
        config.executorService.submit(new jumpdata()); //todo Thread rework

        // 코어 자원소모 감시 시작
        config.executorService.submit(new monitorresource()); //todo Thread rework

        // 서버간 이동 영역 표시
        config.executorService.submit(new visualjump()); //todo Thread rework

        // 기록 시작
        if (config.isLogging()) new Log();

        // 서버기능 시작
        Thread server = new Thread(new Server()); //todo Thread rework
        if (config.isServerenable()) server.start();

        // 테러방지 기능 시작
        // if (config.isAntigrief()) new Anti();

        Events.on(TapConfigEvent.class, e -> {
            if (e.tile.block().name != null && e.player.name() != null && config.isAlertaction()) {
                allsendMessage("tap-config", e.player.name(), e.tile.block().name);
                if(config.isDebug()) log(LogType.log,"antigrief-build-config", e.player.name(), e.tile.block().name, e.tile.x(), e.tile.y());
            }
        });

        Events.on(TapEvent.class, e -> {
            if(isLogin(e.player)) { //todo Global rework
                for (jumpzone data : pluginData.jumpzone) {
                    String ip = data.ip;
                    int port = data.port;

                    if (e.tile.x() > data.start().x && e.tile.x() < data.finish().x) {
                        if (e.tile.y() > data.start().y && e.tile.y() < data.finish().y) {
                            log(LogType.log, "player-jumped", e.player.name(), data.ip);
                            PlayerData player = PlayerData(e.player.uuid()); //todo PlayerDB rework
                            player.connected = false;
                            player.connserver = "none";
                            PlayerDataSave(player);
                            Call.onConnect(e.player.con(), ip, port);
                        }
                    }
                }
            }
        });

        Events.on(WithdrawEvent.class, e->{
            if (e.player.miner().item() != null && e.player.name() != null && config.isAntigrief()) {
                allsendMessage("log-withdraw", e.player.name(), e.player.miner().item().name, e.amount, e.tile.block().name);
                if (config.isDebug()) log(LogType.log,"log-withdraw", e.player.name(), e.player.miner().item().name, e.amount, e.tile.block().name);
                if(state.rules.pvp){
                    if(e.item.flammability > 0.001f) {
                        e.player.sendMessage(bundle(PlayerData(e.player.uuid()).locale, "flammable-disabled"));
                        e.player.miner().clearItem();
                    }
                }
            }
        });

        // 게임오버가 되었을 때
        Events.on(GameOverEvent.class, e -> {
            if (state.rules.pvp) {
                int index = 5;
                for (int a = 0; a < 5; a++) {
                    if (state.teams.get(Team.all()[index]).cores.isEmpty()) {
                        index--;
                    }
                }
                if (index == 1) {
                    for (int i = 0; i < Groups.player.size(); i++) {
                        Playerc player = Groups.player.getByID(i);
                        PlayerData target = PlayerData(player.uuid());
                        if (target.isLogin) {
                            if (player.team().name.equals(e.winner.name)) {
                                target.pvpwincount++;
                            } else if (!player.team().name.equals(e.winner.name)) {
                                target.pvplosecount++;
                            }
                            PlayerDataSet(target);
                        }
                    }
                    pvpteam.clear();
                }
            } else if(state.rules.attackMode){
                for (int i = 0; i < Groups.player.size(); i++) {
                    Playerc player = Groups.player.getByID(i);
                    PlayerData target = PlayerData(player.uuid());
                    if (target.isLogin) {
                        target.attackclear++;
                        PlayerDataSet(target);
                    }
                }
            }
        });

        // 맵이 불러와졌을 때
        Events.on(WorldLoadEvent.class, e -> {
            threads.playtime = LocalTime.of(0,0,0);

            // 전력 노드 정보 초기화
            pluginData.powerblock.clear();
        });

        Events.on(PlayerConnect.class, e -> {
            // 닉네임이 블랙리스트에 등록되어 있는지 확인
            for (String s : pluginData.blacklist) {
                if (e.player.name().matches(s)) {
                    try {
                        Locale locale = geolocation(e.player);
                        Call.onKick(e.player.con(), nbundle(locale, "nickname-blacklisted-kick"));
                        log(LogType.log, "nickname-blacklisted", e.player.name());
                    } catch (Exception ex) {
                        printError(ex);
                    }
                }
            }

            if (config.isStrictname()) {
                if (e.player.name().length() > 32) Call.onKick(e.player.con(), "Nickname too long!");
                if (e.player.name().matches(".*\\[.*].*")) Call.onKick(e.player.con(), "Color tags can't be used for nicknames on this server.");
                if (e.player.name().contains("　")) Call.onKick(e.player.con(), "Don't use blank speical charactor nickname!");
                if (e.player.name().contains(" ")) Call.onKick(e.player.con(), "Nicknames can't be used on this server!");
            }

            /*if(config.isStrictname()){
                if(e.player.name.length() < 3){
                    player.con.kick("The nickname is too short!\n닉네임이 너무 짧습니다!");
                    log(LogType.log,"nickname-short");
                }
                if(e.player.name.matches("^(?=.*\\\\d)(?=.*[~`!@#$%\\\\^&*()-])(?=.*[a-z])(?=.*[A-Z])$")){
                    e.player.con.kick("Server doesn't allow special characters.\n서버가 특수문자를 허용하지 않습니다.");
                    log(LogType.log,"nickname-special", player.name);
                }
            }*/
        });

        // 플레이어가 아이템을 특정 블록에다 직접 가져다 놓았을 때
        Events.on(DepositEvent.class, e -> {
            if(e.player.miner().stack().amount > e.player.unit().itemCapacity()){
                player.con().kick("Invalid request!");
                return;
            }

            // 만약 그 특정블록이 토륨 원자로이며, 맵 설정에서 원자로 폭발이 비활성화 되었을 경우
            if (e.tile.block() == Blocks.thoriumReactor && config.isDetectreactor() && !state.rules.reactorExplosions) {
                pluginData.nukeblock.add(new nukeblock(e.tile, e.player.name()));
                Thread t = new Thread(() -> {
                    try {
                        for (nukeblock data : pluginData.nukeblock) {
                            NuclearReactor.NuclearReactorEntity entity = (NuclearReactor.NuclearReactorEntity) data.tile.entity;
                            if (entity.heat >= 0.01) {
                                sleep(50);
                                allsendMessage("detect-thorium");

                                writeLog(LogType.griefer,nbundle("griefer-detect-reactor-log", getTime(),data.name));
                                Call.onTileDestroyed(data.tile);
                            } else {
                                sleep(1950);
                                if (entity.heat >= 0.01) {
                                    allsendMessage("detect-thorium");
                                    writeLog(LogType.griefer,nbundle("griefer-detect-reactor-log", getTime(),data.name));
                                    Call.onTileDestroyed(data.tile);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        printError(ex);
                    }
                });
                t.start();
            }
            if(config.isAlertaction()) allsendMessage("depositevent", e.player.name(), e.player.miner().item().name, e.tile.block().name);
        });

        // 플레이어가 서버에 들어왔을 때
        Events.on(PlayerJoin.class, e -> {
            players.add(e.player);
            e.player.admin(false);

            e.player.unit().kill();
            e.player.team(Team.derelict);

            Thread t = new Thread(() -> {
                Thread.currentThread().setName(e.player.name() + " Player Join thread");
                PlayerData playerData = getInfo("uuid", e.player.uuid());
                if (config.isLoginenable() && isNocore(e.player)) {
                    if (config.getPasswordmethod().equals("mixed")) {
                        if (!playerData.error) {
                            if (playerData.udid != 0L) {
                                new Thread(() -> Call.onConnect(e.player.con(), hostip, 7060)).start();
                            } else {
                                e.player.sendMessage(bundle(playerData.locale, "autologin"));
                                playerDB.load(e.player, playerData.accountid);
                            }
                        } else {
                            if (playerDB.register(e.player)) {
                                playerDB.load(e.player);
                            } else {
                                Call.onKick(e.player.con(), nbundle("plugin-error-kick"));
                            }
                        }
                    } else if (config.getPasswordmethod().equals("discord")) {
                        if (!playerData.error) {
                            e.player.sendMessage(bundle(playerData.locale, "autologin"));
                            playerDB.load(e.player, playerData.accountid);
                        } else {
                            String message;
                            Locale language = geolocation(e.player);
                            if (config.getPasswordmethod().equals("discord")) {
                                message = nbundle(language, "login-require-discord") + "\n" + config.getDiscordLink();
                            } else {
                                message = nbundle(language, "login-require-password");
                            }
                            Call.onInfoMessage(e.player.con(), message);
                        }
                    } else {
                        if (!playerData.error) {
                            e.player.sendMessage(bundle(playerData.locale, "autologin"));
                            playerDB.load(e.player);
                        } else {
                            String message;
                            Locale language = geolocation(e.player);
                            if (config.getPasswordmethod().equals("discord")) {
                                message = nbundle(language, "login-require-discord") + "\n" + config.getDiscordLink();
                            } else {
                                message = nbundle(language, "login-require-password");
                            }
                            Call.onInfoMessage(e.player.con(), message);
                        }
                    }
                } else {
                    // 로그인 기능이 꺼져있을 때, 바로 계정 등록을 하고 데이터를 로딩함
                    if (!playerData.error) {
                        e.player.sendMessage(bundle(playerData.locale, "autologin"));
                        playerDB.load(e.player);
                    } else {
                        if (playerDB.register(e.player)) {
                            playerDB.load(e.player);
                        } else {
                            Call.onKick(e.player.con(), nbundle("plugin-error-kick"));
                        }
                    }
                }

                // VPN을 사용중인지 확인
                if (config.isAntivpn()) {
                    try {
                        InputStream reader = getClass().getResourceAsStream("/ipv4.txt");
                        BufferedReader br = new BufferedReader(new InputStreamReader(reader));

                        String ip = netServer.admins.getInfo(e.player.uuid()).lastIP;
                        String line;
                        while ((line = br.readLine()) != null) {
                            IpAddressMatcher match = new IpAddressMatcher(line);
                            if (match.matches(ip)) {
                                Call.onKick(e.player.con(), nbundle("antivpn-kick"));
                            }
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }

                // PvP 평화시간 설정
                if (config.isEnableantirush() && state.rules.pvp && threads.playtime.isBefore(config.getAntirushtime())) {
                    state.rules.playerDamageMultiplier = 0f;
                    state.rules.playerHealthMultiplier = 0.001f;
                    onSetRules(state.rules);
                    threads.PvPPeace = true;
                }
            });
            config.executorService.submit(t);
        });

        // 플레이어가 서버에서 탈주했을 때
        Events.on(PlayerLeave.class, e -> {
            players.remove(e.player);
            PlayerData player = PlayerData(e.player.uuid());
            if (player.isLogin) {
                player.connected = false;
                player.connserver = "none";
                if(state.rules.pvp && !state.gameOver) player.pvpbreakout++;
                if(config.getPasswordmethod().equals("discord")){
                    PlayerDataSaveUUID(player, player.accountid);
                } else {
                    PlayerDataSave(player);
                }
            } else {
                PlayerDataRemove(player);
            }
        });

        // 플레이어가 수다떨었을 때
        Events.on(PlayerChatEvent.class, e -> {
            if (isLogin(e.player)) {
                PlayerData playerData = PlayerData(e.player.uuid());
                String check = String.valueOf(e.message.charAt(0));
                // 명령어인지 확인
                if (!check.equals("/")) {
                    if (e.message.matches("(.*쌍[\\S\\s]{0,2}(년|놈).*)|(.*(씨|시)[\\S\\s]{0,2}(벌|빨|발|바).*)|(.*장[\\S\\s]{0,2}애.*)|(.*(병|븅)[\\S\\s]{0,2}(신|쉰|싄).*)|(.*(좆|존|좃)[\\S\\s]{0,2}(같|되|는|나).*)|(.*(개|게)[\\S\\s]{0,2}(같|갓|새|세|쉐).*)|(.*(걸|느)[\\S\\s]{0,2}(레|금).*)|(.*(꼬|꽂|고)[\\S\\s]{0,2}(추|츄).*)|(.*(니|너)[\\S\\s]{0,2}(어|엄|엠|애|m|M).*)|(.*(노)[\\S\\s]{0,1}(애|앰).*)|(.*(섹|쎅)[\\S\\s]{0,2}(스|s|쓰).*)|(ㅅㅂ|ㅄ|ㄷㅊ)|(.*(섹|쎅)[\\S\\s]{0,2}(스|s|쓰).*)|(.*s[\\S\\s]{0,1}e[\\S\\s]{0,1}x.*)")) {
                        Call.onKick(e.player.con, nbundle(playerData.locale, "kick-swear"));
                    } else if (e.message.equals("y") && isvoting) {
                        // 투표가 진행중일때
                        if (Vote.list.contains(e.player.uuid())) {
                            e.player.sendMessage(bundle(playerData.locale, "vote-already"));
                        } else {
                            Vote.list.add(e.player.uuid());
                            int current = Vote.list.size();
                            if (Vote.require - current <= 0) {
                                Vote.cancel();
                                return;
                            }
                            for (Player others : playerGroup.all()) {
                                if (isLogin(others)) others.sendMessage(bundle(PlayerData(others.uuid).locale, "vote-current", current, Vote.require - current));
                            }
                        }
                    } else {
                        if(!playerData.mute) {
                            if (perm.permission.get(playerData.permission).asObject().get("prefix") != null) {
                                if(!playerData.crosschat) Call.sendMessage(perm.permission.get(playerData.permission).asObject().get("prefix").asString().replace("%1", colorizeName(e.player.id, e.player.name)).replaceAll("%2", e.message));
                            } else {
                                if(!playerData.crosschat) Call.sendMessage("[orange]" + colorizeName(e.player.id, e.player.name) + "[orange] :[white] " + e.message);
                            }

                            // 서버간 대화기능 작동
                            if (playerData.crosschat) {
                                if (client.server_active) {
                                    client.request(Request.chat, e.player, e.message);
                                } else if (config.isServerenable()) {
                                    // 메세지를 모든 클라이언트에게 전송함
                                    String msg = "[" + e.player.name + "]: " + e.message;
                                    try {
                                        for (Server.Service ser : Server.list) {
                                            ser.os.writeBytes(Base64.encode(encrypt(msg, ser.spec, ser.cipher)));
                                            ser.os.flush();
                                        }
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                } else if (!config.isClientenable() && !config.isServerenable()) {
                                    e.player.sendMessage(bundle(playerData.locale, "no-any-network"));
                                    playerData.crosschat = false;
                                }
                            }
                        }
                        arc.util.Log.info("<&y{0}: &lm{1}&lg>", e.player.name, e.message);
                    }
                }

                // 마지막 대화 데이터를 DB에 저장함
                playerData.lastchat = e.message;

                // 번역
                if (config.isEnableTranslate()) {
                    try {
                        for (int i = 0; i < Groups.player.size(); i++) {
                            Player p = Groups.player.getByID(i);
                            if (!isNocore(p)) {
                                PlayerData target = PlayerData(p.uuid);
                                String[] support = {"ko", "en", "zh-CN", "zh-TW", "es", "fr", "vi", "th", "id"};
                                String language = target.language;
                                String orignal = playerData.language;
                                if (!language.equals(orignal)) {
                                    boolean found = false;
                                    for (String s : support) {
                                        if (orignal.equals(s)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (found) {
                                        String response = Jsoup.connect("https://naveropenapi.apigw.ntruss.com/nmt/v1/translation")
                                                .method(Connection.Method.POST)
                                                .header("X-NCP-APIGW-API-KEY-ID", config.getClientId())
                                                .header("X-NCP-APIGW-API-KEY", config.getClientSecret())
                                                .data("source", playerData.language)
                                                .data("target", target.language)
                                                .data("text", e.message)
                                                .ignoreContentType(true)
                                                .followRedirects(true)
                                                .execute()
                                                .body();
                                        JsonObject object = JsonValue.readJSON(response).asObject();
                                        if (object.get("error") != null) {
                                            String result = object.get("message").asObject().get("result").asObject().getString("translatedText", "none");
                                            if (target.translate) {
                                                p.sendMessage("[green]" + e.player.name + "[orange]: [white]" + result);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        printError(ex);
                    }
                }

                PlayerDataSet(playerData);
            }
        });

        // 플레이어가 블럭을 건설했을 때
        Events.on(BlockBuildEndEvent.class, e -> {
            if (!e.breaking && e.player != null && e.player.buildRequest() != null && !isNocore(e.player) && e.tile != null && e.player.buildRequest() != null) {
                PlayerData target = PlayerData(e.player.uuid());
                String name = e.tile.block().name;
                try {
                    JsonObject obj = JsonValue.readHjson(root.child("Exp.hjson").reader()).asObject();
                    int blockexp = obj.getInt(name,0);

                    target.lastplacename = e.tile.block().name;
                    target.placecount++;
                    target.exp = target.exp + blockexp;
                    if (e.player.buildRequest().block == Blocks.thoriumReactor) target.reactorcount++;
                } catch (Exception ex) {
                    printError(ex);
                }

                target.grief_build_count++;
                target.grief_tilelist.add(new short[]{e.tile.x,e.tile.y});

                // 메세지 블럭을 설치했을 경우, 해당 블럭을 감시하기 위해 위치를 저장함.
                if (e.tile.entity.block == Blocks.message) {
                    pluginData.messagemonitor.add(new messagemonitor(e.tile));
                }

                // 플레이어가 토륨 원자로를 만들었을 때, 감시를 위해 그 원자로의 위치를 저장함.
                if (e.tile.entity.block == Blocks.thoriumReactor) {
                    pluginData.nukeposition.add(e.tile);
                    pluginData.nukedata.add(e.tile);
                }

                if(config.isDebug() && config.isAntigrief()){
                    log(LogType.log,"antigrief-build-finish", e.player.name, e.tile.block().name, e.tile.x, e.tile.y);
                }

                // 필터 아트 감지
                int sorter_count = 0;
                //int conveyor_count = 0;
                for(short[] t : target.grief_tilelist){
                    Tile tile = world.tile(t[0],t[1]);
                    if(tile != null && tile.block() != null) {
                        if (tile.block() == Blocks.sorter || tile.block() == Blocks.invertedSorter) sorter_count++;
                    }
                    //if(tile.entity.block == Blocks.conveyor || tile.entity.block == Blocks.armoredConveyor || tile.entity.block == Blocks.titaniumConveyor) conveyor_count++;
                }
                if(sorter_count > 20){
                    for(short[] t : target.grief_tilelist){
                        Tile tile = world.tile(t[0],t[1]);
                        if(tile != null && tile.entity != null && tile.entity.block != null) {
                            if (tile.entity.block == Blocks.sorter || tile.entity.block == Blocks.invertedSorter) {
                                Call.onDeconstructFinish(tile,Blocks.air,e.player.id);
                            }
                        }
                    }
                    target.grief_tilelist.clear();
                }

                PlayerDataSet(target);
            }
        });

        // 플레이어가 블럭을 뽀갰을 때
        Events.on(BuildSelectEvent.class, e -> {
            if (e.builder instanceof Player && e.builder.buildRequest() != null && !e.builder.buildRequest().block.name.matches(".*build.*") && e.tile.block() != Blocks.air) {
                if (e.breaking) {
                    PlayerData target = PlayerData(((Player) e.builder).uuid);
                    String name = e.tile.block().name;
                    try {
                        JsonObject obj = JsonValue.readHjson(root.child("Exp.hjson").reader()).asObject();
                        int blockexp = obj.getInt(name, 0);

                        target.lastbreakname = e.tile.block().name;
                        target.breakcount++;
                        target.exp = target.exp + blockexp;
                    } catch (Exception ex) {
                        printError(ex);
                        Call.onKick(((Player) e.builder).con, nbundle(target.locale, "not-logged"));
                    }

                    // 메세지 블럭을 파괴했을 때, 위치가 저장된 데이터를 삭제함
                    if (e.builder.buildRequest().block == Blocks.message) {
                        try {
                            for (int i = 0; i < pluginData.powerblock.size(); i++) {
                                if (pluginData.powerblock.get(i).tile == e.tile) {
                                    pluginData.powerblock.remove(i);
                                    break;
                                }
                            }
                        } catch (Exception ex) {
                            printError(ex);
                        }
                    }

                    // Exp Playing Game (EPG)
                    if (config.isExplimit()) {
                        int level = target.level;
                        try {
                            JsonObject obj = JsonValue.readHjson(root.child("Exp.hjson").reader()).asObject();
                            if (obj.get(name) != null) {
                                int blockreqlevel = obj.getInt(name, 999);
                                if (level < blockreqlevel) {
                                    Call.onDeconstructFinish(e.tile, e.tile.block(), ((Player) e.builder).id);
                                    ((Player) e.builder).sendMessage(nbundle(PlayerData(((Player) e.builder).uuid).locale, "epg-block-require", name, blockreqlevel));
                                }
                            } else {
                                log(LogType.error, "epg-block-not-valid", name);
                            }
                        } catch (Exception ex) {
                            printError(ex);
                        }
                    }

                    /*if(e.builder.buildRequest().block == Blocks.conveyor || e.builder.buildRequest().block == Blocks.armoredConveyor || e.builder.buildRequest().block == Blocks.titaniumConveyor){
                        for(int a=0;a<conveyor.size();a++){
                            if(conveyor.get(a) == e.tile){
                                conveyor.remove(a);
                                break;
                            }
                        }
                    }*/

                    target.grief_destory_count++;
                    // Call.sendMessage(String.valueOf(target.grief_destory_count));
                    // if (target.grief_destory_count > 30) nlog(LogType.log, target.name + " 가 블럭을 빛의 속도로 파괴하고 있습니다.");
                    PlayerDataSet(target);
                }
                if(config.isDebug() && config.isAntigrief()){
                    log(LogType.log,"antigrief-destroy", ((Player) e.builder).name, e.tile.block().name, e.tile.x, e.tile.y);
                }
            }
        });

        // 유닛을 박살냈을 때
        Events.on(UnitDestroyEvent.class, e -> {
            // 뒤진(?) 유닛이 플레이어일때
            if (e.unit instanceof Player) {
                Player player = (Player) e.unit;
                PlayerData target = PlayerData(player.uuid());
                if (!state.teams.get(player.getTeam()).cores.isEmpty()){
                    target.deathcount++;
                    PlayerDataSet(target);
                }
            }

            // 터진 유닛수만큼 카운트해줌
            if (playerGroup != null && Groups.player.size() > 0) {
                for (int i = 0; i < Groups.player.size(); i++) {
                    Player player = Groups.player.getByID(i);
                    PlayerData target = PlayerData(player.uuid());
                    if (!state.teams.get(player.getTeam()).cores.isEmpty()){
                        target.killcount++;
                        PlayerDataSet(target);
                    }
                }
            }
        });

        // 플레이어가 밴당했을 때 공유기능 작동
        Events.on(PlayerBanEvent.class, e -> {
            Thread bansharing = new Thread(() -> {
                if (config.isBanshare() && client.server_active) {
                    client.request(Request.bansync, null, null);
                }

                for (Player player : playerGroup.all()) {
                    player.sendMessage(bundle(PlayerData(player.uuid()).locale, "player-banned", e.player.name));
                    if (netServer.admins.isIDBanned(player.uuid())) {
                        player.con.kick(Packets.KickReason.banned);
                    }
                }
            });
            config.executorService.submit(bansharing);
        });

        // 이건 IP 밴당했을때 작동
        Events.on(PlayerIpBanEvent.class, e -> {
            Thread bansharing = new Thread(() -> {
                if (config.isBanshare() && client.server_active) {
                    client.request(Request.bansync, null, null);
                }
            });
           config.executorService.submit(bansharing);
        });

        // 이건 밴 해제되었을 때 작동
        Events.on(PlayerUnbanEvent.class, e -> {
            if(client.server_active) {
                client.request(Request.unbanid, null, e.player.uuid() + "|<unknown>");
            }
        });

        // 이건 IP 밴이 해제되었을 때 작동
        Events.on(PlayerIpUnbanEvent.class, e -> {
            if(client.server_active) {
                client.request(Request.unbanip, null, "<unknown>|"+e.ip);
            }
        });

        // 로그인 기능이 켜져있을때, 비 로그인 사용자들에게 알림을 해줌
        if (config.isLoginenable()) {
            timer.scheduleAtFixedRate(new login(), 60000, 60000);
        }

        // 1초마다 실행되는 작업 시작
        threads = new Threads();
        timer.scheduleAtFixedRate(threads, 1000, 1000);

        // 30초마다 실행되는 작업 시작
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                PlayerDataSaveAll();
                pluginData.saveall();
            }
        }, 30000, 30000);

        // 1분마다 실행되는 작업 시작
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                int re = 0;
                for (jumpcount value : pluginData.jumpcount) re = re + value.players;
                if(pluginData.average == null) pluginData.average = new ArrayList<>();
                pluginData.average.add(re+Groups.player.size());
            }
        },60000,60000);

        // 롤백 명령어에서 사용될 자동 저장작업 시작
        if(config.isEnableRollback()) timer.scheduleAtFixedRate(new AutoRollback(), config.getSavetime() * 60000, config.getSavetime() * 60000);

        // 0.016초마다 실행 및 서버 종료시 실행할 작업
        int[] tick = {0};
        boolean[] reactor_warn1 = {false};
        boolean[] reactor_warn2 = {false};
        boolean[] reactor_warn3 = {false};
        Events.on(Trigger.update, () -> {
            /*void setText(int orignal, int amount, Item item){
                String color;
                String data;
                int val;
                if (state.teams.get(Team.sharded).cores.first().items.has(item)) {
                    val = amount - orignal;
                    if (val > 0) {
                        color = "[#00f]+";
                    } else {
                        color = "[red]-";
                    }
                    data = "[]" + item.name + ": " + color + val + "/s\n";
                    scancore_text.append(data);
                }
            }*/
            /*for (Player p : playerGroup.all()) {
                PlayerData playerData = PlayerData(p.uuid);
                if(data.isLogin){
                    String message = "Exp: "+data.exp+"\nLevel: "+data.level+"\nReq: "+data.reqtotalexp+"\nServer received pointer location:"+Math.round(p.pointerX)+"/"+Math.round(p.pointerY);
                    Call.onInfoPopup(p.con,message,0.02f, Align.left,0,0,0,0);
                }
            }*/

            /*for(Tile tile : conveyor){
                if(tile.block() != null) {
                    if (tile.rotation() != world.tile(tile.x, tile.y).rotation()) {
                        nlog(LogType.log, "orignal: " + tile.rotation() + ", current: " + world.tile(tile.x, tile.y).rotation());
                        Call.onConstructFinish(world.tile(tile.x, tile.y), tile.entity.block, 0, tile.rotation(), Team.sharded, false);
                    }
                }
            }*/

            if(config.isBorder()) {
                for (Player p : playerGroup.all()) {
                    if (p.x > world.width() * 8 || p.x < 0 || p.y > world.height() * 8 || p.y < 0)
                        Call.onPlayerDeath(p);
                }
            }

            if (tick[0] == 30) {
                try {
                    // 메세지 블럭에다 전력량을 표시 (반드시 게임 시간과 똑같이 작동되어야만 함)
                    for (int i = 0; i < pluginData.powerblock.size(); i++) {
                        if (pluginData.powerblock.get(i).tile.block() != Blocks.message) {
                            pluginData.powerblock.remove(i);
                            return;
                        }

                        float current;
                        float product;
                        float using;
                        try {
                            current = pluginData.powerblock.get(i).tile.entity.power.graph.getPowerBalance() * 60;
                            using = pluginData.powerblock.get(i).tile.entity.power.graph.getPowerNeeded() * 60;
                            product = pluginData.powerblock.get(i).tile.entity.power.graph.getPowerProduced() * 60;
                        } catch (Exception ignored) {
                            pluginData.powerblock.remove(i);
                            return;
                        }
                        String text = "Power status\n" +
                                "Current: [sky]" + Math.round(current) + "[]\n" +
                                "Using: [red]" + Math.round(using) + "[]\n" +
                                "Production: [green]" + Math.round(product) + "[]";
                        Call.setMessageBlockText(null, pluginData.powerblock.get(i).tile, text);
                    }
                    // 타이머 초기화
                    tick[0] = 0;
                    reactor_warn1[0] = false;
                    reactor_warn2[0] = false;
                    reactor_warn3[0] = false;
                } catch (Exception ignored) {}
            } else {
                tick[0]++;
            }

            // 핵 폭발감지
            if(config.isDetectreactor()) {
                for (int i = 0; i < pluginData.nukedata.size(); i++) {
                    Tile target = pluginData.nukedata.get(i);
                    try {
                        NuclearReactor.NuclearReactorEntity entity = (NuclearReactor.NuclearReactorEntity) target.entity;
                        if (entity.heat >= 0.2f && entity.heat <= 0.39f && !reactor_warn1[0]) {
                            allsendMessage("thorium-overheat-green", Math.round(entity.heat * 100), target.x, target.y);
                            reactor_warn1[0] = true;
                        }
                        if (entity.heat >= 0.4f && entity.heat <= 0.79f && !reactor_warn2[0]) {
                            allsendMessage("thorium-overheat-yellow", Math.round(entity.heat * 100), target.x, target.y);
                            reactor_warn2[0] = true;
                        }
                        if (entity.heat >= 0.8f && entity.heat <= 0.95f && !reactor_warn3[0]) {
                            allsendMessage("thorium-overheat-red", Math.round(entity.heat * 100), target.x, target.y);
                            reactor_warn3[0] = true;
                        }
                        if (entity.heat >= 0.95f) {
                            for (int a = 0; a < Groups.player.size(); a++) {
                                Player p = playerGroup.all().get(a);
                                p.sendMessage(bundle(PlayerData(p.uuid).locale, "thorium-overheat-red", Math.round(entity.heat * 100), target.x, target.y));
                                if (p.isAdmin) {
                                    p.setNet(target.x * 8, target.y * 8);
                                }
                            }
                            Call.onDeconstructFinish(target, Blocks.air, 0);
                            allsendMessage("thorium-removed");
                        }
                    } catch (Exception ignored) {
                        pluginData.nukedata.remove(i);
                        break;
                    }
                }
            }
        });

        listener = new ApplicationListener() {
            @Override
            public void dispose() {
                try {
                    boolean error = false;

                    PlayerDataSaveAll(); // 플레이어 데이터 저장
                    pluginData.saveall(); // 플러그인 데이터 저장
                    config.executorService.shutdownNow(); // 스레드 종료
                    config.singleService.shutdownNow(); // 로그 스레드 종료
                    timer.cancel(); // 일정 시간마다 실행되는 스레드 종료
                    if (isvoting) Vote.cancel(); // 투표 종료
                    if (jda != null) jda.shutdownNow(); // Discord 서비스 종료
                    closeconnect(); // DB 연결 종료
                    if (config.isServerenable()) {
                        try {
                            Iterator<Server.Service> servers = Server.list.iterator();
                            while(servers.hasNext()){
                                Server.Service ser = servers.next();
                                ser.os.close();
                                ser.in.close();
                                ser.socket.close();
                                servers.remove();
                            }

                            Server.serverSocket.close();
                            server.interrupt();

                            log(LogType.log, "server-thread-disabled");
                        } catch (Exception e) {
                            error = true;
                            printError(e);
                            log(LogType.error, "server-thread-disable-error");
                        }
                    }

                    // 클라이언트 종료
                    if (client.server_active) {
                        client.request(Request.exit, null, null);
                        log(LogType.log, "client-thread-disabled");
                    }

                    // 모든 이벤트 서버 종료
                    for (Process value : pluginData.process) value.destroy();
                    if (!error) {
                        log(LogType.log, "thread-disabled");
                    } else {
                        log(LogType.log, "thread-not-dead");
                    }
                } catch (Exception ignored){}
            }
        };

        Core.app.addListener(listener);

        Events.on(ServerLoadEvent.class, e-> {
            // 예전 DB 변환
            if(config.isOldDBMigration()) new DataMigration().MigrateDB();
            // 업데이트 확인
            if(config.isUpdate()) {
                log(LogType.client,"client-checking-version");
                try {
                    JsonObject json = JsonValue.readJSON(Jsoup.connect("https://api.github.com/repos/kieaer/Essentials/releases/latest").ignoreContentType(true).execute().body()).asObject();

                    for(int a=0;a<mods.list().size;a++){
                        if(mods.list().get(a).meta.name.equals("Essentials")){
                            plugin_version = mods.list().get(a).meta.version;
                        }
                    }

                    DefaultArtifactVersion latest = new DefaultArtifactVersion(json.getString("tag_name", plugin_version));
                    DefaultArtifactVersion current = new DefaultArtifactVersion(plugin_version);

                    if (latest.compareTo(current) > 0) {
                        log(LogType.client,"version-new");
                        net.dispose();
                        Thread t = new Thread(() -> {
                            try {
                                log(LogType.log, nbundle("update-description", json.get("tag_name")));
                                System.out.println(json.getString("body","No description found."));
                                System.out.println(nbundle("plugin-downloading-standby"));
                                timer.cancel();
                                if (config.isServerenable()) {
                                    try {
                                        for (Server.Service ser : Server.list) {
                                            ser.interrupt();
                                            ser.os.close();
                                            ser.in.close();
                                            ser.socket.close();
                                            Server.list.remove(ser);
                                        }
                                        Server.serverSocket.close();
                                        server.interrupt();
                                    } catch (Exception ignored) {}
                                }
                                if (client.server_active) {
                                    client.request(Request.exit, null, null);
                                }
                                config.executorService.shutdown();
                                closeconnect();

                                URLDownload(new URL(json.get("assets").asArray().get(0).asObject().getString("browser_download_url", null)),
                                        Core.settings.getDataDirectory().child("mods/Essentials.jar").file(),
                                        nbundle("plugin-downloading"),
                                        nbundle("plugin-downloading-done"), null);
                                Core.app.exit();
                                System.exit(0);
                            }catch (Exception ex){
                                System.out.println("\n"+nbundle("plugin-downloading-fail"));
                                printError(ex);
                                Core.app.exit();
                                System.exit(0);
                            }
                        });
                        t.start();
                    } else if (latest.compareTo(current) == 0) {
                        log(LogType.client,"version-current");
                    } else if (latest.compareTo(current) < 0) {
                        log(LogType.client,"version-devel");
                    }
                } catch (Exception ex) {
                    printError(ex);
                }
            }

            // Discord 봇 시작
            if(config.getPasswordmethod().equals("discord") || config.getPasswordmethod().equals("mixed")){
                discord = new Discord();
                discord.start();
            }

            // 채팅 포맷 변경
            netServer.admins.addChatFilter((player, text) -> null);
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.removeCommand("ban");
        handler.removeCommand("unban");

        handler.register("gendocs", "Generate Essentials README.md", (arg) -> {
            log(LogType.log,"readme-generating");
            // README.md 생성
            String header = "# Essentials\n" +
                    "Add more commands to the server.\n\n" +
                    "I'm getting a lot of suggestions.<br>\n" +
                    "Please submit your idea to this repository issues or Mindustry official discord!\n\n" +
                    "## Requirements for running this plugin\n" +
                    "This plugin does a lot of disk read/write operations depending on the features usage.\n\n" +
                    "### Minimum\n" +
                    "CPU: Athlon 200GE or Intel i5 2300<br>\n" +
                    "RAM: 20MB<br>\n" +
                    "Disk: HDD capable of more than 2MB/s random read/write.\n\n" +
                    "### Recommand\n" +
                    "CPU: Ryzen 3 2200G or Intel i3 8100<br>\n" +
                    "RAM: 50MB<br>\n" +
                    "Disk: HDD capable of more than 5MB/s random read/write.\n\n" +
                    "## Installation\n\n" +
                    "Put this plugin in the ``<server folder location>/config/mods`` folder.\n\n" +
                    "## Essentials 9.0 Plans\n" +
                    "- [ ] Server\n" +
                    "  - [ ] Control server using Web server\n" +
                    "- [x] PlayerDB\n" +
                    "  - [x] DB Server without MySQL/MariaDB..\n" +
                    "- [x] Internal\n" +
                    "  - [x] Fix server can't shutdown\n" +
                    "  - [x] Make ban reason\n" +
                    "- [ ] Anti-grief\n" +
                    "  - [ ] Make anti-filter art\n" +
                    "  - [ ] Make anti-fast build/break destroy\n" +
                    "  - [ ] Make detect custom client\n" +
                    "- [ ] Soruce code rebuild\n" +
                    "  - [ ] core\n" +
                    "    - [ ] Discord\n" +
                    "    - [ ] PlayerDB\n" +
                    "  - [ ] net\n" +
                    "    - [ ] Client\n" +
                    "    - [ ] Server\n" +
                    "  - [ ] special\n" +
                    "    - [ ] DataMigration\n" +
                    "    - [ ] DriverLoader\n" +
                    "  - [ ] utils\n" +
                    "    - [ ] Bundle\n" +
                    "    - [ ] Config\n" +
                    "  - [ ] Global\n" +
                    "  - [ ] Main\n" +
                    "  - [ ] Threads\n" +
                    "    - [ ] login\n" +
                    "    - [ ] changename\n" +
                    "    - [ ] AutoRollback\n" +
                    "    - [ ] eventserver\n" +
                    "    - [ ] ColorNick\n" +
                    "    - [ ] monitorresource\n" +
                    "    - [ ] Vote\n" +
                    "    - [ ] jumpdata\n" +
                    "    - [ ] visualjump\n\n";
            String serverdoc = "## Server commands\n\n| Command | Parameter | Description |\n|:---|:---|:--- |\n";
            String clientdoc = "## Client commands\n\n| Command | Parameter | Description |\n|:---|:---|:--- |\n";
            String tmp;
            List<String> servercommands = new ArrayList<>(Arrays.asList(
                    "help","version","exit","stop","host","maps","reloadmaps","status",
                    "mods","mod","js","say","difficulty","rules","fillitems","playerlimit",
                    "config","subnet-ban","whitelisted","whitelist-add","whitelist-remove",
                    "shuffle","nextmap","kick","ban","bans","unban","admin","unadmin",
                    "admins","runwave","load","save","saves","gameover","info","search", "gc"
            ));
            List<String> clientcommands = new ArrayList<>(Arrays.asList(
                    "help","t","sync"
            ));
            String gentime = "\nREADME.md Generated time: "+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());

            StringBuilder tempbuild = new StringBuilder();
            for(int a=0;a<netServer.clientCommands.getCommandList().size;a++){
                CommandHandler.Command command = netServer.clientCommands.getCommandList().get(a);
                boolean dup = false;
                for(String as : clientcommands){
                    if(command.text.equals(as)){
                        dup = true;
                        break;
                    }
                }
                if(!dup){
                    String temp = "| "+command.text+" | "+StringUtils.encodeHtml(command.paramText)+" | "+command.description+" |\n";
                    tempbuild.append(temp);
                }
            }
            tmp = header+clientdoc+tempbuild.toString()+"\n";
            tempbuild = new StringBuilder();

            for(CommandHandler.Command command : handler.getCommandList()) {
                boolean dup = false;
                for(String as : servercommands){
                    if(command.text.equals(as)){
                        dup = true;
                        break;
                    }
                }
                if(!dup){
                    String temp = "| "+command.text+" | "+StringUtils.encodeHtml(command.paramText)+" | "+command.description+" |\n";
                    tempbuild.append(temp);
                }
            }
            root.child("README.md").writeString(tmp+serverdoc+tempbuild.toString()+gentime);
            log(LogType.log,"success");
        });
        handler.register("admin", "<name>","Set admin status to player.", (arg) -> {
            if(arg.length == 0) {
                log(LogType.warn,"no-parameter");
                return;
            }
            Player player = playerGroup.find(p -> p.name.equals(arg[0]));
            if(player == null){
                log(LogType.warn,"player-not-found");
                return;
            }
            for (JsonObject.Member data : perm.permission) {
                if(data.getName().equals("new_admin")){
                    PlayerData p = PlayerData(player.uuid());
                    p.permission = "new_admin";
                    PlayerDataSave(p);
                    log(LogType.log, "success");
                    break;
                }
            }
            log(LogType.log,"use-setperm");
        });
        handler.register("allinfo", "<name>", "Show player information.", (arg) -> {
            if(arg.length == 0) {
                log(LogType.warn,"no-parameter");
                return;
            }
            Thread t = new Thread(() -> {
                try{
                    String sql = "SELECT * FROM players WHERE name=? OR accountid=?";
                    PreparedStatement ptmt = conn.prepareStatement(sql);
                    ptmt.setString(1,arg[0]);
                    ptmt.setString(2,arg[0]);
                    ResultSet rs = ptmt.executeQuery();
                    nlog(LogType.log,"Data line start.");
                    while(rs.next()){
                        String datatext = "\nPlayer Information\n" +
                                "========================================\n" +
                                "Name: "+rs.getString("name")+"\n" +
                                "UUID: "+rs.getString("uuid")+"\n" +
                                "Country: "+rs.getString("country")+"\n" +
                                "Block place: "+rs.getInt("placecount")+"\n" +
                                "Block break: "+rs.getInt("breakcount")+"\n" +
                                "Kill units: "+rs.getInt("killcount")+"\n" +
                                "Death count: "+rs.getInt("deathcount")+"\n" +
                                "Join count: "+rs.getInt("joincount")+"\n" +
                                "Kick count: "+rs.getInt("kickcount")+"\n" +
                                "Level: "+rs.getInt("level")+"\n" +
                                "XP: "+rs.getString("reqtotalexp")+"\n" +
                                "First join: "+rs.getString("firstdate")+"\n" +
                                "Last join: "+rs.getString("lastdate")+"\n" +
                                "Playtime: "+rs.getString("playtime")+"\n" +
                                "Attack clear: "+rs.getInt("attackclear")+"\n" +
                                "PvP Win: "+rs.getInt("pvpwincount")+"\n" +
                                "PvP Lose: "+rs.getInt("pvplosecount")+"\n" +
                                "PvP Surrender: "+rs.getInt("pvpbreakout")+"\n" +
                                "Permission: "+rs.getString("permission");
                        nlog(LogType.log,datatext);
                    }
                    rs.close();
                    ptmt.close();
                    nlog(LogType.log,"Data line end.");
                }catch (Exception e){
                    printError(e);
                }
            });
            config.executorService.execute(t);
        });
        handler.register("ban", "<type-id/name/ip> <reason> <username/IP/ID...>", "Ban a person.", arg -> {
            if(arg[0].equals("id")){
                netServer.admins.banPlayerID(arg[2]);
                if(accountban(true, arg[2], arg[1])){
                    log(LogType.player,"success");
                } else {
                    log(LogType.playerwarn,"failed");
                }
            }else if(arg[0].equals("name")){
                Player target = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[2]));
                if(target != null){
                    netServer.admins.banPlayer(target.uuid);
                    if(accountban(true, target.uuid, arg[1])){
                        log(LogType.player,"success");
                    } else {
                        log(LogType.playerwarn,"failed");
                    }
                }else{
                    err("No matches found.");
                }
            }else if(arg[0].equals("ip")){
                netServer.admins.banPlayerIP(arg[2]);
                info("Banned.");
            }else{
                log(LogType.warn,"wrong-command");
            }

            for(Player player : playerGroup.all()){
                if(netServer.admins.isIDBanned(player.uuid())){
                    allsendMessage("player-banned",player.name);
                    player.con.kick(Packets.KickReason.banned);
                }
            }
        });
        handler.register("bansync", "Ban list synchronization from main server.", (arg) -> {
            if(!config.isServerenable()){
                if(config.isBanshare()){
                    client.request(Request.bansync, null, null);
                } else {
                    log(LogType.warn,"banshare-disabled");
                }
            } else {
                log(LogType.warn,"banshare-server");
            }
        });
        handler.register("banreason", "<name...>", "Show player ban reason", (arg) -> {
            if(arg.length < 1) {
                log(LogType.warn,"no-parameter");
                return;
            }
            for(banned b : pluginData.banned){
                if(b.name.equals(arg[0])){
                    log(LogType.player,"ban-reason");
                    break;
                }
            }
            log(LogType.log,"player-not-found");
        });
        handler.register("blacklist", "<add/remove> <nickname>", "Block special nickname.", arg -> {
            if(arg.length < 1) {
                log(LogType.warn,"no-parameter");
                return;
            }
            if (arg[0].equals("add")) {
                pluginData.blacklist.add(arg[1]);
                log(LogType.log, "blacklist-add", arg[1]);
            } else if (arg[0].equals("remove")) {
                for (int i = 0; i < pluginData.blacklist.size(); i++) {
                    if (pluginData.blacklist.get(i).equals(arg[1])) {
                        pluginData.blacklist.remove(i);
                        log(LogType.log, "blacklist-remove", arg[1]);
                        return;
                    }
                }
            } else {
                log(LogType.warn, "blacklist-invalid");
            }
        });
        handler.register("reset", "<zone/count/total>", "Clear a server-to-server jumping zone data.", arg -> {
            if(arg.length == 0) {
                log(LogType.warn,"no-parameter");
                return;
            }
            switch(arg[0]){
                case "zone":
                    pluginData.jumpzone.clear();
                    log(LogType.log,"jump-reset", "zone");
                    break;
                case "count":
                    pluginData.jumpcount.clear();
                    log(LogType.log,"jump-reset", "count");
                    break;
                case "total":
                    pluginData.jumptotal.clear();
                    log(LogType.log,"jump-reset", "total");
                    break;
                default:
                    log(LogType.warn,"Invalid option!");
                    break;
            }
        });
        /*handler.register("reconnect", "Reconnect remote server (Essentials server only!)", arg -> {
            if(config.isClientenable()){
                if(server_active) client.request(Request.exit, null, null);
                log(LogType.client,"server-connecting");
                client.wakeup();
            }
            log(LogType.client,"db-connecting");
            closeconnect();
            openconnect();
            log(LogType.log,"success");
        });*/
        handler.register("restart", "Plugin restart", arg -> {
            /* 플러그인 종료 */
            try {
                PlayerDataSaveAll(); // 플레이어 데이터 저장
                pluginData.saveall(); // 플러그인 데이터 저장
                config.executorService.shutdownNow(); // 스레드 종료
                config.singleService.shutdownNow(); // 로그 스레드 종료
                timer.cancel(); // 일정 시간마다 실행되는 스레드 종료
                if (isvoting) Vote.cancel(); // 투표 종료
                if (jda != null) jda.shutdownNow(); // Discord 서비스 종료
                closeconnect(); // DB 연결 종료
                if (config.isServerenable()) {
                    for (Server.Service ser : Server.list) {
                        ser.interrupt();
                        ser.os.close();
                        ser.in.close();
                        ser.socket.close();
                        Server.list.remove(ser);
                    }
                    Server.serverSocket.close();
                }

                // 클라이언트 종료
                if (client.server_active) {
                    client.request(Request.exit, null, null);
                    log(LogType.log, "client-thread-disabled");
                }

                // 모든 이벤트 서버 종료
                for (Process value : pluginData.process) value.destroy();

                Events.dispose();
                Core.app.removeListener(listener);

                /* 플러그인 시작 */
                //mods.getMod(Main.class).main.getClass().getMethods().length
                new Main();
                Events.fire(new ServerLoadEvent());
                for(Player p : playerGroup){
                    player.sendMessage(bundle(PlayerData(p.uuid).locale,"plugin-restart"));
                    Events.fire(new PlayerJoin(p));
                }
                log(LogType.log,"plugin-reloaded");
            } catch (Exception e){
                printError(e);
            }
        });
        handler.register("unban", "<ip/ID>", "Completely unban a person by IP or ID.", arg -> {
            if(netServer.admins.unbanPlayerID(arg[0])){
                info("Unbanned player.", arg[0]);
            } else if(netServer.admins.unbanPlayerIP(arg[0])) {
                info("Unbanned player.", arg[0]);
            } else {
                err("That IP/ID is not banned!");
            }
        });
        handler.register("unadminall", "<default_group_name>", "Remove all player admin status", arg -> {
            for (JsonObject.Member data : perm.permission) {
                if(data.getName().equals(arg[0])){
                    for(Player player : playerGroup) player.isAdmin = false;
                    writeData("UPDATE players SET permission = ?", arg[0]);
                    log(LogType.log, "success");
                    return;
                }
            }
            log(LogType.warn,"perm-group-not-found");
        });
        handler.register("kickall", "Kick all players.",  arg -> {
            for(int a=0;a<Groups.player.size();a++){
                Player others = playerGroup.all().get(a);
                Call.onKick(others.con, "All kick players by administrator.");
            }
            nlog(LogType.log,"It's done.");
        });
        handler.register("kill", "<username>", "Kill target player.", arg -> {
            if(arg.length == 0) {
                log(LogType.warn,"no-parameter");
                return;
            }
            Player other = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if(other != null){
                other.kill();
            } else {
                log(LogType.warn,"player-not-found");
            }
        });
        handler.register("mute","<Player_name>", "Mute/unmute player", (arg, player) -> {
            Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if (other == null) {
                log(LogType.warn,"player-not-found");
            } else {
                PlayerData target = PlayerData(other.uuid);
                if(target.mute){
                    target.mute = false;
                    log(LogType.log,"player-unmute",target.name);
                } else {
                    target.mute = true;
                    log(LogType.log,"player-muted",target.name);
                }
                PlayerDataSet(target);
            }
        });
        handler.register("nick", "<name> <newname...>", "Set player nickname", (arg) -> {
            if(arg.length < 1) {
                log(LogType.warn,"no-parameter");
                return;
            }
            try{
                Player player = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
                player.name = arg[1];
                PlayerData(player.uuid()).name = arg[1];
                log(LogType.log,"player-nickname-change-to", arg[0], arg[1]);
            }catch (Exception e){
                printError(e);
                log(LogType.warn,"player-not-found");
            }
        });
        handler.register("pvp", "<anticoal/timer> [time...]", "Set gamerule with PvP mode.", arg -> {
            /*
            if(Vars.state.rules.pvp){
                switch(arg[0]){
                    case "anticoal":
                        break;
                    case "timer":
                        break;
                    default:
                        break;
                }
            }
            */
            nlog(LogType.log,"Currently not supported!");
        });
        handler.register("setperm", "<player_name> <group>", "Set player permission group", arg -> {
            Player player = playerGroup.find(p -> p.name.equals(arg[0]));
            if(player == null){
                log(LogType.warn,"player-not-found");
                return;
            }
            for (JsonObject.Member data : perm.permission) {
                if(data.getName().equals(arg[1])){
                    PlayerData p = PlayerData(player.uuid());
                    p.permission = arg[1];
                    PlayerDataSave(p);
                    log(LogType.log, "success");
                    return;
                }
            }
            log(LogType.playererr,"perm-group-not-found");
        });
        handler.register("sync", "<player>", "Force sync request from the target player.", arg -> {
            Player other = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if(other != null){
                Call.onWorldDataBegin(other.con);
                netServer.sendWorldData(other);
            } else {
                log(LogType.warn,"player-not-found");
            }
        });
        handler.register("team","<name>", "Change target player team.", (arg) -> {
            Player other = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if(other != null){
                int i = other.getTeam().id+1;
                while(i != other.getTeam().id){
                    if (i >= Team.all().length) i = 0;
                    if(!state.teams.get(Team.all()[i]).cores.isEmpty()){
                        other.setTeam(Team.all()[i]);
                        break;
                    }
                    i++;
                }
                other.kill();
            } else {
                log(LogType.warn,"player-not-found");
            }
        });
        handler.register("tempban", "<player_name> <time> <reason>", "Temporarily ban player. time unit: 1 hours.", arg -> {
            int bantimeset = Integer.parseInt(arg[1]);
            Player other = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if(other == null){
                log(LogType.warn,"player-not-found");
                return;
            }
            addtimeban(other.name, other.uuid, bantimeset, arg[2]);
            log(LogType.log,"tempban", other.name, arg[1]);
            other.con.kick("Temp kicked");
        });
        handler.register("reloadmaps", "Reload all maps from disk.", arg -> {
            int beforeMaps = maps.all().size;
            maps.reload();
            maplist = Vars.maps.all();
            if(maps.all().size > beforeMaps){
                info("&lc{0}&ly new map(s) found and reloaded.", maps.all().size - beforeMaps);
            }else{
                info("&lyMaps reloaded.");
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.removeCommand("vote");
        handler.removeCommand("votekick");

        handler.<Player>register("alert", "Turn on/off alerts", (arg, player) -> {
            if(!checkperm(player,"alert")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if (playerData.alert){
                playerData.alert = false;
                player.sendMessage(bundle(playerData.locale, "alert-disable"));
            } else {
                playerData.alert = true;
                player.sendMessage(bundle(playerData.locale, "alert"));
            }
            PlayerDataSet(playerData);
        });
        handler.<Player>register("ch", "Send chat to another server.", (arg, player) -> {
            if(!checkperm(player,"ch")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if (playerData.crosschat) {
                playerData.crosschat = false;
                player.sendMessage(bundle(playerData.locale, "crosschat-disable"));
            } else {
                playerData.crosschat = true;
                player.sendMessage(bundle(playerData.locale, "crosschat"));
            }
            PlayerDataSet(playerData);
        });
        handler.<Player>register("changepw", "<new_password>", "Change account password", (arg, player) -> {
            if(!checkperm(player,"changepw")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if(!checkpw(player, playerData.accountid, arg[1])){
                player.sendMessage(bundle(playerData.locale, "need-new-password"));
                return;
            }
            try{
                Class.forName("org.mindrot.jbcrypt.BCrypt");
                playerData.accountpw = BCrypt.hashpw(arg[0], BCrypt.gensalt(11));
                PlayerDataSave(playerData);
                player.sendMessage(bundle(playerData.locale,"success"));
            } catch (ClassNotFoundException e) {
                printError(e);
            }
        });
        handler.<Player>register("chars", "<Text...>", "Make pixel texts", (arg, player) -> {
            if(!checkperm(player,"chars")) return;
            HashMap<String, int[]> letters = new HashMap<>();

            letters.put("A", new int[]{0,1,1,1,1,1,0,1,0,0,1,0,1,0,0,0,1,1,1,1});
            letters.put("B", new int[]{1,1,1,1,1,1,0,1,0,1,1,0,1,0,1,0,1,0,1,0});
            letters.put("C", new int[]{0,1,1,1,0,1,0,0,0,1,1,0,0,0,1,1,0,0,0,1});
            letters.put("D", new int[]{1,1,1,1,1,1,0,0,0,1,1,0,0,0,1,0,1,1,1,0});
            letters.put("E", new int[]{1,1,1,1,1,1,0,1,0,1,1,0,1,0,1,1,0,1,0,1});
            letters.put("F", new int[]{1,1,1,1,1,1,0,1,0,0,1,0,1,0,0,1,0,1,0,0});
            letters.put("G", new int[]{0,1,1,1,0,1,0,0,0,1,1,0,1,0,1,0,0,1,1,1});
            letters.put("H", new int[]{1,1,1,1,1,0,0,1,0,0,0,0,1,0,0,1,1,1,1,1});
            letters.put("I", new int[]{1,0,0,0,1,1,1,1,1,1,1,0,0,0,1});
            letters.put("J", new int[]{1,0,0,1,0,1,0,0,0,1,1,1,1,1,0,1,0,0,0,0});
            letters.put("K", new int[]{1,1,1,1,1,0,0,1,0,0,0,1,0,1,0,1,0,0,0,1});
            letters.put("L", new int[]{1,1,1,1,1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
            letters.put("M", new int[]{1,1,1,1,1,0,1,0,0,0,0,0,1,0,0,0,1,0,0,0,1,1,1,1,1});
            letters.put("N", new int[]{1,1,1,1,1,0,1,0,0,0,0,0,1,0,0,1,1,1,1,1});
            letters.put("O", new int[]{0,1,1,1,0,1,0,0,0,1,1,0,0,0,1,1,0,0,0,1,0,1,1,1,0});
            letters.put("P", new int[]{1,1,1,1,1,1,0,1,0,0,1,0,1,0,0,0,1,0,0,0});
            letters.put("Q", new int[]{0,1,1,1,0,1,0,0,0,1,1,0,0,0,1,0,1,1,1,0,0,0,0,0,1});
            letters.put("R", new int[]{1,1,1,1,1,1,0,1,0,0,1,0,1,0,0,0,1,0,1,1});
            letters.put("S", new int[]{1,1,1,0,1,1,0,1,0,1,1,0,1,0,1,1,0,1,1,1});
            letters.put("T", new int[]{1,0,0,0,0,1,0,0,0,0,1,1,1,1,1,1,0,0,0,0,1,0,0,0,0});
            letters.put("U", new int[]{1,1,1,1,0,0,0,0,0,1,0,0,0,0,1,1,1,1,1,0});
            letters.put("V", new int[]{1,1,1,0,0,0,0,0,1,0,0,0,0,0,1,0,0,0,1,0,1,1,1,0,0});
            letters.put("W", new int[]{1,1,1,1,0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,1,1,1,1,1,0});
            letters.put("X", new int[]{1,0,0,0,1,0,1,0,1,0,0,0,1,0,0,0,1,0,1,0,1,0,0,0,1});
            letters.put("Y", new int[]{1,1,0,0,0,0,0,1,0,0,0,0,0,1,1,0,0,1,0,0,1,1,0,0,0});
            letters.put("Z", new int[]{1,0,0,1,1,1,0,1,0,1,1,1,0,0,1});

            letters.put("!", new int[]{1,1,1,1,0,1});
            letters.put("?", new int[]{0,1,0,0,0,0,1,0,0,0,0,0,1,0,1,1,0,1,0,1,0,0,0,0});

            letters.put(" ", new int[]{0,0,0,0,0,0,0,0,0,0});

            String[] texts = arg[0].split("");
            Tile tile = world.tile(player.tileX(),player.tileY());

            for (String text : texts) {
                ArrayList<int[]> pos = new ArrayList<>();
                int[] target = letters.get(text.toUpperCase());
                int xv = 0;
                int yv = 0;
                switch (target.length) {
                    case 20:
                        xv = 5;
                        yv = 4;
                        break;
                    case 15:
                        xv = 5;
                        yv = 3;
                        break;
                    case 18:
                        xv = 6;
                        yv = 3;
                        break;
                    case 25:
                        xv = 5;
                        yv = 5;
                        break;
                    case 6:
                        xv = 6;
                        yv = 1;
                        break;
                    case 10:
                        xv = 2;
                        yv = 5;
                        break;
                }
                for(int y=0;y<yv;y++){
                    for(int x=0;x<xv;x++){
                        pos.add(new int[]{y,-x});
                    }
                }
                for(int a=0;a<pos.size();a++){
                    if(target[a] == 1) {
                        Call.onConstructFinish(world.tile(tile.x+pos.get(a)[0], tile.y+pos.get(a)[1]), Blocks.plastaniumWall, 0, (byte) 0, Team.sharded, true);
                    } else {
                        Call.onDeconstructFinish(world.tile(tile.x+pos.get(a)[0], tile.y+pos.get(a)[1]), Blocks.air, 0);
                    }
                }
                tile = world.tile(tile.x + (xv+1), tile.y);
            }
        });
        handler.<Player>register("color", "Enable color nickname", (arg, player) -> {
            if (!checkperm(player, "color")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if (playerData.colornick) {
                playerData.colornick = false;
                player.sendMessage(bundle(playerData.locale, "colornick-disable"));
            } else {
                playerData.colornick = true;
                player.sendMessage(bundle(playerData.locale, "colornick"));
            }
            PlayerDataSet(playerData);
        });
        handler.<Player>register("difficulty", "<difficulty>", "Set server difficulty", (arg, player) -> {
            if (!checkperm(player, "difficulty")) return;
            PlayerData playerData = PlayerData(player.uuid());
            try {
                Difficulty.valueOf(arg[0]);
                player.sendMessage(bundle(playerData.locale, "difficulty-set", arg[0]));
            } catch (IllegalArgumentException e) {
                player.sendMessage(bundle(playerData.locale, "difficulty-not-found", arg[0]));
            }
        });
        handler.<Player>register("killall","Kill all enemy units", (arg, player) -> {
            if (!checkperm(player, "killall")) return;
            for(int a=0;a<Team.all().length;a++) unitGroup.all().each(Unit::kill);
            player.sendMessage(bundle(PlayerData(player.uuid()).locale,"success"));
        });
        handler.<Player>register("event", "<host/join> <roomname> [map] [gamemode]", "Host your own server", (arg, player) -> {
            if(!checkperm(player,"event")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Thread t = new Thread(() -> {
                switch (arg[0]) {
                    case "host":
                        Thread work = new Thread(() -> {
                            PlayerData target = PlayerData(player.uuid());
                            if (target.level > 20 || player.isAdmin) {
                                if (arg.length == 2) {
                                    player.sendMessage(bundle(playerData.locale, "event-host-no-mapname"));
                                    return;
                                }
                                if (arg.length == 3) {
                                    player.sendMessage(bundle(playerData.locale, "event-host-no-gamemode"));
                                    return;
                                }
                                player.sendMessage(bundle(playerData.locale, "event-making"));

                                String[] range = config.getEventport().split("-");
                                int firstport = Integer.parseInt(range[0]);
                                int lastport = Integer.parseInt(range[1]);
                                int customport = ThreadLocalRandom.current().nextInt(firstport,lastport+1);

                                pluginData.eventservers.add(new eventservers(arg[1],customport));

                                Threads.eventserver es = new Threads.eventserver(arg[1],arg[2],arg[3],customport);
                                es.roomname = arg[1];
                                es.map = arg[2];
                                if (arg[3].equals("wave")) {
                                    es.gamemode = "wave";
                                } else {
                                    es.gamemode = arg[3];
                                }
                                es.customport = customport;
                                es.start();
                                try {
                                    es.join();
                                } catch (InterruptedException e) {
                                    printError(e);
                                }
                                log(LogType.log,"event-host-opened", player.name, customport);

                                target.connected = false;
                                target.connserver = "none";
                                PlayerDataSave(target);
                                if(isLocal(netServer.admins.getInfo(player.uuid()).lastIP)){
                                    Call.onConnect(player.con,"127.0.0.1",customport);
                                } else {
                                    Call.onConnect(player.con, hostip, customport);
                                }
                                nlog(LogType.log,hostip+":"+customport);
                            } else {
                                player.sendMessage(bundle(playerData.locale, "event-level"));
                            }
                        });
                        work.start();
                        break;
                    case "join":
                        for (eventservers server : pluginData.eventservers) {
                            if (server.roomname.equals(arg[1])) {
                                PlayerData val = PlayerData(player.uuid());
                                val.connected = false;
                                val.connserver = "none";
                                PlayerDataSave(val);
                                Call.onConnect(player.con, hostip, server.port);
                                nlog(LogType.log,hostip+":"+server.port);
                                break;
                            }
                        }
                        break;
                    default:
                        player.sendMessage(bundle(playerData.locale, "wrong-command"));
                        break;
                }
            });
            t.start();
        });
        handler.<Player>register("email", "<key>", "Email Authentication", (arg, player) -> {
            for(maildata data : pluginData.emailauth){
                if(data.uuid.equals(player.uuid())){
                    if(data.authkey.equals(arg[0])){
                        if(playerDB.register(player, data.id, data.pw, "emailauth", data.email)){
                            playerDB.load(player);
                            return;
                        }
                    } else {
                        player.sendMessage("You have entered an incorrect authentication key.");
                    }
                } else {
                    player.sendMessage("You didn't enter your email information when you registered.");
                }
            }
        });
        handler.<Player>register("help", "[page]", "Show command lists", (arg, player) -> {
            if(arg.length > 0 && !Strings.canParseInt(arg[0])){
                player.sendMessage(bundle(PlayerData(player.uuid()).locale, "page-number"));
                return;
            }

            ArrayList<String> temp = new ArrayList<>();
            for(int a=0;a<netServer.clientCommands.getCommandList().size;a++){
                CommandHandler.Command command = netServer.clientCommands.getCommandList().get(a);
                if(checkperm(player,command.text) || command.text.equals("t") || command.text.equals("sync")){
                    temp.add("[orange] /"+command.text+" [white]"+command.paramText+" [lightgray]- "+command.description+"\n");
                }
            }

            List<String> deduped = temp.stream().distinct().collect(Collectors.toList());

            StringBuilder result = new StringBuilder();
            int perpage = 8;
            int page = arg.length > 0 ? Strings.parseInt(arg[0]) : 1;
            int pages = Mathf.ceil((float)deduped.size() / perpage);

            page --;

            if(page > pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            result.append(Strings.format("[orange]-- Commands Page[lightgray] {0}[gray]/[lightgray]{1}[orange] --\n", (page+1), pages));
            for(int a=perpage*page;a<Math.min(perpage*(page+1), deduped.size());a++){
                result.append(deduped.get(a));
            }
            player.sendMessage(result.toString().substring(0, result.length()-1));
        });
        handler.<Player>register("info", "Show your information", (arg, player) -> {
            if (!checkperm(player, "info")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Locale locale = playerData.locale;
            String datatext = "[#DEA82A]" + nbundle(playerData.locale, "player-info") + "[]\n" +
                    "[#2B60DE]====================================[]\n" +
                    "[green]" + nbundle(locale, "player-name") + "[] : " + player.name + "[white]\n" +
                    "[green]" + nbundle(locale, "player-country") + "[] : " + locale.getDisplayCountry() + "\n" +
                    "[green]" + nbundle(locale, "player-placecount") + "[] : " + playerData.placecount + "\n" +
                    "[green]" + nbundle(locale, "player-breakcount") + "[] : " + playerData.breakcount + "\n" +
                    "[green]" + nbundle(locale, "player-killcount") + "[] : " + playerData.killcount + "\n" +
                    "[green]" + nbundle(locale, "player-deathcount") + "[] : " + playerData.deathcount + "\n" +
                    "[green]" + nbundle(locale, "player-joincount") + "[] : " + playerData.joincount + "\n" +
                    "[green]" + nbundle(locale, "player-kickcount") + "[] : " + playerData.kickcount + "\n" +
                    "[green]" + nbundle(locale, "player-level") + "[] : " + playerData.level + "\n" +
                    "[green]" + nbundle(locale, "player-reqtotalexp") + "[] : " + playerData.reqtotalexp + "\n" +
                    "[green]" + nbundle(locale, "player-firstdate") + "[] : " + playerData.firstdate + "\n" +
                    "[green]" + nbundle(locale, "player-lastdate") + "[] : " + playerData.lastdate + "\n" +
                    "[green]" + nbundle(locale, "player-playtime") + "[] : " + playerData.playtime + "\n" +
                    "[green]" + nbundle(locale, "player-attackclear") + "[] : " + playerData.attackclear + "\n" +
                    "[green]" + nbundle(locale, "player-pvpwincount") + "[] : " + playerData.pvpwincount + "\n" +
                    "[green]" + nbundle(locale, "player-pvplosecount") + "[] : " + playerData.pvplosecount + "\n" +
                    "[green]" + nbundle(locale, "player-pvpbreakout") + "[] : " + playerData.pvpbreakout;
            Call.onInfoMessage(player.con, datatext);
        });
        handler.<Player>register("jump", "<zone/count/total> <touch> [serverip] [range]", "Create a server-to-server jumping zone.", (arg, player) -> {
            if (!checkperm(player, "jump")) return;
            PlayerData playerData = PlayerData(player.uuid());
            switch (arg[0]){
                case "zone":
                    if(arg.length != 4){
                        player.sendMessage(bundle(playerData.locale, "jump-incorrect"));
                        return;
                    }
                    int size;
                    try {
                        size = Integer.parseInt(arg[3]);
                    } catch (Exception ignored) {
                        player.sendMessage(bundle(playerData.locale, "jump-not-int"));
                        return;
                    }

                    int tf = player.tileX() + size;
                    int ty = player.tileY() + size;

                    pluginData.jumpzone.add(new jumpzone(world.tile(player.tileX(), player.tileY()),world.tile(tf,ty),Boolean.parseBoolean(arg[1]),arg[2]));
                    player.sendMessage(bundle(playerData.locale, "jump-added"));
                    break;
                case "count":
                    pluginData.jumpcount.add(new jumpcount(world.tile(player.tileX(),player.tileY()),arg[2],0,0));
                    player.sendMessage(bundle(playerData.locale, "jump-added"));
                    break;
                case "total":
                    // tilex, tiley, total players, number length
                    pluginData.jumptotal.add(new jumptotal(world.tile(player.tileX(),player.tileY()),0,0));
                    player.sendMessage(bundle(playerData.locale, "jump-added"));
                    break;
                default:
                    player.sendMessage(bundle(playerData.locale, "command-invalid"));
            }
        });
        handler.<Player>register("kickall", "Kick all players", (arg, player) -> {
            if (!checkperm(player, "kickall")) return;
            Vars.netServer.kickAll(Packets.KickReason.kick);
        });
        handler.<Player>register("kill", "<player>", "Kill player.", (arg, player) -> {
            if (!checkperm(player, "kill")) return;
            Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            if (other == null) {
                player.sendMessage(bundle(PlayerData(player.uuid()).locale, "player-not-found"));
                return;
            }
            Player.onPlayerDeath(other);
        });
        handler.<Player>register("login", "<id> <password>", "Access your account", (arg, player) -> {
            PlayerData playerData = PlayerData(player.uuid());
            if (config.isLoginenable()) {
                if(!isLogin(player)) {
                    if (PlayerDB.login(player, arg[0], arg[1])) {
                        if(config.getPasswordmethod().equals("discord")){
                            playerDB.load(player, arg[0]);
                        } else {
                            playerDB.load(player);
                        }
                        player.sendMessage(bundle(playerData.locale, "login-success"));
                    } else {
                        player.sendMessage("[green][EssentialPlayer] [scarlet]Login failed/로그인 실패!!");
                    }
                } else {
                    if(config.getPasswordmethod().equals("mixed")){
                        if (PlayerDB.login(player, arg[0], arg[1])) Call.onConnect(player.con, hostip, 7060);
                    } else {
                        player.sendMessage("[green][EssentialPlayer] [scarlet]You're already logged./이미 로그인한 상태입니다.");
                    }
                }
            } else {
                player.sendMessage(bundle(playerData.locale, "login-not-use"));
            }
        });
        handler.<Player>register("logout","Log-out of your account.", (arg, player) -> {
            if(!checkperm(player,"logout")) return;
            if(config.isLoginenable()) {
                PlayerData playerData = PlayerData(player.uuid());
                playerData.connected = false;
                playerData.connserver = "none";
                playerData.uuid = "LogoutAAAAA=";
                PlayerDataSave(playerData);
                Call.onKick(player.con, nbundle("logout"));
            } else {
                player.sendMessage(bundle(PlayerData(player.uuid()).locale, "login-not-use"));
            }
        });
        handler.<Player>register("maps", "[page]", "Show server maps", (arg, player) -> {
            if(!checkperm(player,"maps")) return;
            StringBuilder build = new StringBuilder();
            int page = arg.length > 0 ? Strings.parseInt(arg[0]) : 1;
            int pages = Mathf.ceil((float)maplist.size / 6);

            page --;
            if(page > pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            build.append("[green]==[white] Server maps page ").append(page).append("/").append(pages).append(" [green]==[white]\n");
            for(int a=6*page;a<Math.min(6 * (page + 1), maplist.size);a++){
                build.append("[gray]").append(a).append("[] ").append(maplist.get(a).name()).append("\n");
            }
            player.sendMessage(build.toString());
        });
        handler.<Player>register("me", "<text...>", "broadcast * message", (arg, player) -> {
            if(!checkperm(player,"me")) return;
            Call.sendMessage("[orange]*[] " + player.name + "[white] : " + arg[0]);
        });
        handler.<Player>register("motd", "Show server motd.", (arg, player) -> {
            if(!checkperm(player,"motd")) return;
            String motd = getmotd(player);
            int count = motd.split("\r\n|\r|\n").length;
            if (count > 10) {
                Call.onInfoMessage(player.con, motd);
            } else {
                player.sendMessage(motd);
            }
        });
        handler.<Player>register("players", "Show players list", (arg, player) -> {
            if(!checkperm(player,"players")) return;
            StringBuilder build = new StringBuilder();
            int page = arg.length > 0 ? Strings.parseInt(arg[0]) : 1;
            int pages = Mathf.ceil((float)players.size / 6);

            page --;
            if(page > pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            build.append("[green]==[white] Players list page ").append(page).append("/").append(pages).append(" [green]==[white]\n");
            for(int a=6*page;a<Math.min(6 * (page + 1), players.size);a++){
                build.append("[gray]").append(a).append("[] ").append(players.get(a).name).append("\n");
            }
            player.sendMessage(build.toString());
        });
        handler.<Player>register("save", "Auto rollback map early save", (arg, player) -> {
            if (!checkperm(player, "save")) return;
            Fi file = saveDirectory.child(config.getSlotnumber() + "." + saveExtension);
            SaveIO.save(file);
            player.sendMessage(bundle(PlayerData(player.uuid()).locale, "mapsaved"));
        });
        handler.<Player>register("reset", "<zone/count/total> [ip]", "Remove a server-to-server jumping zone data.", (arg, player) -> {
            if (!checkperm(player, "reset")) return;
            PlayerData playerData = PlayerData(player.uuid());
            switch(arg[0]){
                case "zone":
                    for(int a = 0; a< pluginData.jumpzone.size(); a++){
                        if(arg.length != 2){
                            player.sendMessage(bundle(playerData.locale,"no-parameter"));
                            return;
                        }
                        if(arg[1].equals(pluginData.jumpzone.get(a).ip)) {
                            pluginData.jumpzone.remove(a);
                            for (Thread value : visualjump.thread) {
                                value.interrupt();
                            }
                            visualjump.thread.clear();
                            visualjump.main();
                            player.sendMessage(bundle(playerData.locale, "success"));
                            break;
                        }
                    }
                    break;
                case "count":
                    pluginData.jumpcount.clear();
                    player.sendMessage(bundle(playerData.locale,"jump-reset","count"));
                    break;
                case "total":
                    pluginData.jumptotal.clear();
                    player.sendMessage(bundle(playerData.locale,"jump-reset","total"));
                    break;
                default:
                    player.sendMessage(bundle(playerData.locale,"command-invalid"));
                    break;
            }
        });
        switch (config.getPasswordmethod()) {
            case "email":
                handler.<Player>register("register", "<accountid> <password> <email>", "Register account", (arg, player) -> {
                    if (config.isLoginenable()) {
                        if (playerDB.register(player, arg[0], arg[1], "email", arg[2])) {
                            playerDB.load(player);
                            player.sendMessage("[green][Essentials] [white]Register success!/계정 등록 성공!");
                        } else {
                            player.sendMessage("[green][Essentials] [scarlet]Register failed/계정 등록 실패!");
                        }
                    } else {
                        player.sendMessage(bundle(PlayerData(player.uuid()).locale, "login-not-use"));
                    }
                });
                break;
                /*
            case "sms":
                handler.<Player>register("register", "<accountid> <password> <phone-number>", "Register account", (arg, player) -> {
                    if (config.isLoginenable()) {
                        PlayerDB playerdb = new PlayerDB();
                        if (playerdb.register(player, arg[0], arg[1], "sms", arg[2])) {
                            setTeam(player);
                            Call.onPlayerDeath(player);
                            player.sendMessage("[green][Essentials] [white]Register success!/계정 등록 성공!");
                        } else {
                            player.sendMessage("[green][Essentials] [scarlet]Register failed/계정 등록 실패!");
                        }
                    } else {
                        player.sendMessage(bundle(player, "login-not-use"));
                    }
                });
                break;*/
            case "password":
                handler.<Player>register("register", "<accountid> <password>", "Register account", (arg, player) -> {
                    if (config.isLoginenable()) {
                        if (playerDB.register(player, arg[0], arg[1], "password")) {
                            if (Vars.state.rules.pvp) {
                                int index = player.getTeam().id + 1;
                                while (index != player.getTeam().id) {
                                    if (index >= Team.all().length) {
                                        index = 0;
                                    }
                                    if (!Vars.state.teams.get(Team.all()[index]).cores.isEmpty()) {
                                        player.setTeam(Team.all()[index]);
                                        break;
                                    }
                                    index++;
                                }
                            } else {
                                player.setTeam(Team.sharded);
                            }
                            Call.onPlayerDeath(player);
                            player.sendMessage("[green][Essentials] [white]Register success!/계정 등록 성공!");
                        } else {
                            player.sendMessage("[green][Essentials] [scarlet]Register failed/계정 등록 실패!");
                        }
                    } else {
                        player.sendMessage(bundle(PlayerData(player.uuid()).locale, "login-not-use"));
                    }
                });
                break;
            case "discord":
                handler.<Player>register("register", "Register account", (arg, player) -> player.sendMessage("Join discord and use !signup command!\n" + config.getDiscordLink()));
                break;
            /*case "mixed":
                handler.<Player>register("register", "<accountid> <password>", "Register account", (arg, player) -> {
                    if (config.isLoginenable()) {
                        if (playerDB.register(player, arg[0], arg[1], true)) {
                            PlayerDataRemove(player.uuid());
                            Thread t = new Thread(() -> Call.onConnect(player.con,getip(),7060));
                            t.start();
                        } else {
                            player.sendMessage("[green][Essentials] [scarlet]Register failed/계정 등록 실패!");
                        }
                    } else {
                        player.sendMessage(bundle(player, "login-not-use"));
                    }
                });
                break;*/
        }
        handler.<Player>register("spawn", "<mob_name> <count> [team] [playername]", "Spawn mob in player position", (arg, player) -> {
            if (!checkperm(player, "spawn")) return;
            PlayerData playerData = PlayerData(player.uuid());
            UnitType targetunit;
            switch (arg[0]) {
                case "draug":
                    targetunit = UnitTypes.draug;
                    break;
                case "spirit":
                    targetunit = UnitTypes.spirit;
                    break;
                case "phantom":
                    targetunit = UnitTypes.phantom;
                    break;
                case "wraith":
                    targetunit = UnitTypes.wraith;
                    break;
                case "ghoul":
                    targetunit = UnitTypes.ghoul;
                    break;
                case "revenant":
                    targetunit = UnitTypes.revenant;
                    break;
                case "lich":
                    targetunit = UnitTypes.lich;
                    break;
                case "reaper":
                    targetunit = UnitTypes.reaper;
                    break;
                case "dagger":
                    targetunit = UnitTypes.dagger;
                    break;
                case "crawler":
                    targetunit = UnitTypes.crawler;
                    break;
                case "titan":
                    targetunit = UnitTypes.titan;
                    break;
                case "fortress":
                    targetunit = UnitTypes.fortress;
                    break;
                case "eruptor":
                    targetunit = UnitTypes.eruptor;
                    break;
                case "chaosArray":
                    targetunit = UnitTypes.chaosArray;
                    break;
                case "eradicator":
                    targetunit = UnitTypes.eradicator;
                    break;
                default:
                    player.sendMessage(bundle(playerData.locale, "mob-not-found"));
                    return;
            }
            int count;
            try {
                count = Integer.parseInt(arg[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(bundle(playerData.locale, "mob-spawn-not-number"));
                return;
            }
            if(config.getSpawnlimit() == count){
                player.sendMessage(bundle(playerData.locale, "spawn-limit"));
                return;
            }
            Team targetteam = null;
            if (arg.length >= 3) {
                switch (arg[2]) {
                    case "sharded":
                        targetteam = Team.sharded;
                        break;
                    case "blue":
                        targetteam = Team.blue;
                        break;
                    case "crux":
                        targetteam = Team.crux;
                        break;
                    case "derelict":
                        targetteam = Team.derelict;
                        break;
                    case "green":
                        targetteam = Team.green;
                        break;
                    case "purple":
                        targetteam = Team.purple;
                        break;
                    default:
                        player.sendMessage(bundle(playerData.locale, "team-not-found"));
                        return;
                }
            }
            Player targetplayer = null;
            if (arg.length >= 4) {
                Player target = playerGroup.find(p -> p.name.equals(arg[3]));
                if (target == null) {
                    player.sendMessage(bundle(playerData.locale, "player-not-found"));
                    return;
                } else {
                    targetplayer = target;
                }
            }
            if (targetteam != null) {
                if (targetplayer != null) {
                    for (int i = 0; count > i; i++) {
                        BaseUnit baseUnit = targetunit.create(targetplayer.getTeam());
                        baseUnit.set(targetplayer.getX(), targetplayer.getY());
                        baseUnit.add();
                    }
                } else {
                    for (int i = 0; count > i; i++) {
                        BaseUnit baseUnit = targetunit.create(targetteam);
                        baseUnit.set(player.getX(), player.getY());
                        baseUnit.add();
                    }
                }
            } else {
                for (int i = 0; count > i; i++) {
                    BaseUnit baseUnit = targetunit.create(player.getTeam());
                    baseUnit.set(player.getX(), player.getY());
                    baseUnit.add();
                }
            }
        });
        handler.<Player>register("setperm", "<player_name> <group>", "Set player permission", (arg, player) -> {
            if(!checkperm(player,"setperm")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Player target = playerGroup.find(p -> p.name.equals(arg[0]));
            if(target == null){
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
                return;
            }
            for (JsonObject.Member perm : perm.permission) {
                if(perm.getName().equals(arg[0])){
                    PlayerData val = PlayerData(target.uuid);
                    val.permission = arg[1];
                    PlayerDataSave(val);
                    player.sendMessage(bundle(playerData.locale, "success"));
                    target.sendMessage(bundle(playerData.locale,"perm-changed"));
                    return;
                }
            }
            player.sendMessage(bundle(playerData.locale, "perm-group-not-found"));
        });
        handler.<Player>register("spawn-core","<smail/normal/big>", "Make new core", (arg, player) -> {
            if(!checkperm(player,"spawn-core")) return;
            Block core = Blocks.coreShard;
            switch(arg[0]){
                case "normal":
                    core = Blocks.coreFoundation;
                    break;
                case "big":
                    core = Blocks.coreNucleus;
                    break;
            }
            Call.onConstructFinish(world.tile(player.tileX(),player.tileY()), core,0,(byte)0,player.getTeam(),false);
        });
        handler.<Player>register("setmech","<Mech> [player]", "Set player mech", (arg, player) -> {
            if(!checkperm(player,"setmech")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Mech mech = Mechs.starter;
            switch(arg[0]){
                case "alpha":
                    mech = Mechs.alpha;
                    break;
                case "dart":
                    mech = Mechs.dart;
                    break;
                case "delta":
                    mech = Mechs.glaive;
                    break;
                case "javalin":
                    mech = Mechs.javelin;
                    break;
                case "omega":
                    mech = Mechs.omega;
                    break;
                case "tau":
                    mech = Mechs.tau;
                    break;
                case "trident":
                    mech = Mechs.trident;
                    break;
            }
            if(arg.length == 1){
                for(Player p : playerGroup.all()){
                    p.mech = mech;
                }
            } else {
                Player target = playerGroup.find(p -> p.name.equals(arg[1]));
                if (target == null) {
                    player.sendMessage(bundle(playerData.locale, "player-not-found"));
                    return;
                }
                target.mech = mech;
            }
            player.sendMessage(bundle(playerData.locale,"success"));
        });
        handler.<Player>register("status", "Show server status", (arg, player) -> {
            if(!checkperm(player,"status")) return;
            PlayerData playerData = PlayerData(player.uuid());
            player.sendMessage(nbundle(playerData.locale, "server-status"));
            player.sendMessage("[#2B60DE]========================================[]");
            float fps = Math.round((int) 60f / Time.delta());
            int idb = 0;
            int ipb = 0;

            Array<PlayerInfo> bans = Vars.netServer.admins.getBanned();
            for (PlayerInfo ignored : bans) {
                idb++;
            }

            Array<String> ipbans = Vars.netServer.admins.getBannedIPs();
            for (String ignored : ipbans) {
                ipb++;
            }
            int bancount = idb + ipb;
            player.sendMessage(nbundle(playerData.locale, "server-status-banstat", fps, Vars.Groups.player.size(), bancount, idb, ipb, threads.playtime, threads.uptime, plugin_version));
        });
        handler.<Player>register("suicide", "Kill yourself.", (arg, player) -> {
            if(!checkperm(player,"suicide")) return;
            Player.onPlayerDeath(player);
            if (playerGroup != null && Groups.player.size() > 0) {
                allsendMessage("suicide", player.name);
            }
        });
        handler.<Player>register("team", "[Team...]", "Change team (PvP only)", (arg, player) -> {
            if(!checkperm(player,"team")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if (Vars.state.rules.pvp) {
                int i = player.getTeam().id + 1;
                while (i != player.getTeam().id) {
                    if (i >= Team.all().length) i = 0;
                    if (!Vars.state.teams.get(Team.all()[i]).cores.isEmpty()) {
                        player.setTeam(Team.all()[i]);
                        break;
                    }
                    i++;
                }
                Call.onPlayerDeath(player);
            } else {
                player.sendMessage(bundle(playerData.locale, "command-only-pvp"));
            }
        });
        handler.<Player>register("tempban", "<player> <time> <reason>", "Temporarily ban player. time unit: 1 hours", (arg, player) -> {
            if (!checkperm(player, "tempban")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Player other = null;
            for (Player p : playerGroup.all()) {
                boolean result = p.name.contains(arg[0]);
                if (result) {
                    other = p;
                }
            }
            if (other != null) {
                int bantimeset = Integer.parseInt(arg[1]);
                PlayerDB.addtimeban(other.name, other.uuid, bantimeset, arg[2]);
                other.con.kick("Temp kicked");
                for (int a = 0; a < Groups.player.size(); a++) {
                    Player current = playerGroup.all().get(a);
                    PlayerData target = PlayerData(current.uuid);
                    current.sendMessage(bundle(target.locale, "ban-temp", other.name, player.name));
                }
            } else {
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
            }
        });
        handler.<Player>register("time", "Show server time", (arg, player) -> {
            if(!checkperm(player,"time")) return;
            PlayerData playerData = PlayerData(player.uuid());
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd a hh:mm.ss");
            String nowString = now.format(dateTimeFormatter);
            player.sendMessage(bundle(playerData.locale, "servertime", nowString));
        });
        handler.<Player>register("tp", "<player>", "Teleport to other players", (arg, player) -> {
            if(!checkperm(player,"tp")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if(player.isMobile){
                player.sendMessage(bundle(playerData.locale, "tp-not-support"));
                return;
            }
            Player other = null;
            for (Player p : playerGroup.all()) {
                boolean result = p.name.contains(arg[0]);
                if (result) {
                    other = p;
                }
            }
            if (other == null) {
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
                return;
            }
            player.setNet(other.getX(), other.getY());
        });
        handler.<Player>register("tpp", "<player> <player>", "Teleport to other players", (arg, player) -> {
            if (!checkperm(player, "tpp")) return;
            PlayerData playerData = PlayerData(player.uuid());
            Player other1 = null;
            Player other2 = null;
            for (Player p : playerGroup.all()) {
                boolean result1 = p.name.contains(arg[0]);
                if (result1) {
                    other1 = p;
                }
                boolean result2 = p.name.contains(arg[1]);
                if (result2) {
                    other2 = p;
                }
            }

            if (other1 == null || other2 == null) {
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
                return;
            }
            if (!other1.isMobile || !other2.isMobile) {
                other1.setNet(other2.x, other2.y);
            } else {
                player.sendMessage(bundle(playerData.locale, "tp-ismobile"));
            }
        });
        handler.<Player>register("tppos", "<x> <y>", "Teleport to coordinates", (arg, player) -> {
            if(!checkperm(player,"tppos")) return;
            PlayerData playerData = PlayerData(player.uuid());
            int x;
            int y;
            try{
                x = Integer.parseInt(arg[0]);
                y = Integer.parseInt(arg[1]);
            }catch (Exception ignored){
                player.sendMessage(bundle(playerData.locale, "tp-not-int"));
                return;
            }
            player.setNet(x, y);
        });
        handler.<Player>register("tr", "Enable/disable Translate all chat", (arg, player) -> {
            if(!checkperm(player,"tr")) return;
            PlayerData playerData = PlayerData(player.uuid());
            if (playerData.translate) {
                playerData.translate = false;
                player.sendMessage(bundle(playerData.locale, "translate"));
            } else {
                playerData.translate = true;
                player.sendMessage(bundle(playerData.locale, "translate-disable"));
            }
            PlayerDataSet(playerData);
        });
        if(config.isVoteEnable()) {
            handler.<Player>register("vote", "<mode> [parameter...]", "Voting system (Use /vote to check detail commands)", (arg, player) -> {
                if (!checkperm(player, "vote")) return;
                PlayerData playerData = PlayerData(player.uuid());
                if (isvoting) {
                    player.sendMessage(bundle(playerData.locale, "vote-in-processing"));
                    return;
                }
                if (arg.length == 2) {
                    if (arg[0].equals("kick")) {
                        Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(arg[1]));
                        if (other == null) other = players.get(Integer.parseInt(arg[1]));
                        if (other == null) {
                            player.sendMessage(bundle(playerData.locale, "player-not-found"));
                            return;
                        }
                        if (other.isAdmin) {
                            player.sendMessage(bundle(playerData.locale, "vote-target-admin"));
                            return;
                        }
                        // 강퇴 투표
                        new Vote(player, arg[0], other);
                    } else if (arg[0].equals("map")) {
                        // 맵 투표
                        mindustry.maps.Map world = maps.all().find(map -> map.name().equalsIgnoreCase(arg[1].replace('_', ' ')) || map.name().equalsIgnoreCase(arg[1]));
                        if (world == null) world = maplist.get(Integer.parseInt(arg[1]));
                        if (world == null) {
                            player.sendMessage(bundle(playerData.locale, "vote-map-not-found"));
                        } else {
                            new Vote(player, arg[0], world);
                        }
                    }
                } else {
                    if (arg.length == 0) {
                        player.sendMessage(bundle(playerData.locale, "vote-list"));
                        return;
                    }
                    if (arg[1].equals("gamemode")) {
                        player.sendMessage(bundle(playerData.locale, "vote-list-gamemode"));
                        return;
                    }
                    if (arg[0].equals("map") || arg[0].equals("kick")) {
                        player.sendMessage(bundle(playerData.locale, "vote-map-not-found"));
                        return;
                    }
                    // 게임 오버, wave 넘어가기, 롤백
                    new Vote(player, arg[0]);
                }
            });
        }
        handler.<Player>register("weather","<day,eday,night,enight>","Change map light", (arg, player) ->{
            if(!checkperm(player,"weather")) return;
            // Command idea from Minecraft EssentialsX and Quezler's plugin!
            // Useful with the Quezler's plugin.
            state.rules.lighting = true;
            switch (arg[0]){
                case "day":
                    state.rules.ambientLight.a = 0f;
                    break;
                case "eday":
                    state.rules.ambientLight.a = 0.3f;
                    break;
                case "night":
                    state.rules.ambientLight.a = 0.7f;
                    break;
                case "enight":
                    state.rules.ambientLight.a = 0.85f;
                    break;
                default:
                    return;
            }
            Call.onSetRules(state.rules);
            player.sendMessage("DONE!");
        });
        handler.<Player>register("mute","<Player_name>", "Mute/unmute player", (arg, player) -> {
            if(!checkperm(player,"mute")) return;
            Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(arg[0]));
            PlayerData playerData = PlayerData(player.uuid());
            if (other == null) {
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
            } else {
                PlayerData target = PlayerData(other.uuid);
                if(target.mute){
                    target.mute = false;
                    player.sendMessage(bundle(playerData.locale,"player-unmute",target.name));
                } else {
                    target.mute = true;
                    player.sendMessage(bundle(playerData.locale,"player-muted", target.name));
                }
                PlayerDataSet(target);
            }
        });
        handler.<Player>register("votekick", "[player_name]", "Player kick starts voting.", (arg, player) -> {
            if(!checkperm(player,"votekick")) return;
            Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(arg[1]));
            PlayerData playerData = PlayerData(player.uuid());
            if (other == null) other = players.get(Integer.parseInt(arg[1]));
            if (other == null) {
                player.sendMessage(bundle(playerData.locale, "player-not-found"));
                return;
            }
            if (other.isAdmin){
                player.sendMessage(bundle(playerData.locale, "vote-target-admin"));
                return;
            }

            new Vote(player, arg[0], other);
        });
    }
}