package essentials;

import essentials.core.Exp;
import essentials.core.PlayerDB;
import io.anuke.arc.ApplicationListener;
import io.anuke.arc.Core;
import io.anuke.arc.Events;
import io.anuke.arc.collection.Array;
import io.anuke.arc.files.FileHandle;
import io.anuke.arc.util.Log;
import io.anuke.mindustry.Vars;
import io.anuke.mindustry.content.Blocks;
import io.anuke.mindustry.core.GameState;
import io.anuke.mindustry.entities.type.Player;
import io.anuke.mindustry.game.EventType;
import io.anuke.mindustry.game.EventType.BlockBuildEndEvent;
import io.anuke.mindustry.game.EventType.BuildSelectEvent;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.io.SaveIO;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemType;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import org.codehaus.plexus.util.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static essentials.Global.*;
import static essentials.core.PlayerDB.getData;
import static essentials.core.PlayerDB.writeData;
import static essentials.special.PingServer.pingServer;
import static essentials.utils.Config.*;
import static io.anuke.mindustry.Vars.*;

public class Threads extends TimerTask implements Runnable{
    public static String playtime;
    public static String uptime;
    public static boolean peacetime;
    public static JSONArray nukeposition = new JSONArray();
    public static ArrayList<Process> process = new ArrayList<>();

    @Override
    public void run() {
        // Player playtime counting
        new playtime().start();

        // Temporarily ban players time counting
        new bantime().start();

        // Map playtime counting
        new maptime().start();

        // Server uptime counting
        new uptime().start();

        // Vote monitoring
        //executorService.execute(new checkvote());

        // client players counting in servername
        new changename().start();

        // Save server to server jump data
        Core.settings.getDataDirectory().child("mods/Essentials/data/jumpdata.json").writeString(jumpzone.toString());
        Core.settings.getDataDirectory().child("mods/Essentials/data/jumpcount.json").writeString(jumpcount.toString());
        Core.settings.getDataDirectory().child("mods/Essentials/data/jumpall.json").writeString(jumpall.toString());

        // If world loaded
        if(state.is(GameState.State.playing)) {
            // server to server monitoring
            new jumpzone().start();

            // client players counting
            new jumpcheck().start();

            // all client players counting
            new jumpall().start();

            // check resource use fast
            if(config.isScanresource() && !Vars.state.teams.get(Team.sharded).cores.isEmpty()) {
                executorService.execute(new monitorresource());
            }

            // Check thorium reactor in cryofluid
            // executorService.execute(new checkthorium());
        }
    }

    class playtime extends Thread {
        @Override
        public void run(){
            
            try{
                if(playerGroup.size() > 0){
                    for(int i = 0; i < playerGroup.size(); i++){
                        Player player = playerGroup.all().get(i);
                        Thread t = new Thread(() -> {
                            if (!Vars.state.teams.get(player.getTeam()).cores.isEmpty()) {
                                JSONObject db = new JSONObject();
                                try {
                                    db = getData(player.uuid);
                                } catch (Exception e) {
                                    printStackTrace(e);
                                }
                                String data;
                                if (db.has("playtime")) {
                                    data = db.getString("playtime");
                                } else {
                                    return;
                                }
                                SimpleDateFormat format = new SimpleDateFormat("HH:mm.ss");
                                Date d1;
                                Calendar cal;
                                String newTime = null;
                                try {
                                    d1 = format.parse(data);
                                    cal = Calendar.getInstance();
                                    cal.setTime(d1);
                                    cal.add(Calendar.SECOND, 1);
                                    newTime = format.format(cal.getTime());
                                } catch (ParseException e1) {
                                    printStackTrace(e1);
                                }

                                // Exp caculating
                                int exp = db.getInt("exp");
                                int newexp = exp + (int) (Math.random() * 5);

                                writeData("UPDATE players SET exp = '" + newexp + "', playtime = '" + newTime + "' WHERE uuid = '" + player.uuid + "'");
                                Exp.exp(player.name, player.uuid);
                            }
                        });
                        t.start();
                    }
                }
            }catch (Exception ex){
                printStackTrace(ex);
            }
            
        }
    }
    static class bantime extends Thread {
        @Override
        public void run(){
            try{
                String db = Core.settings.getDataDirectory().child("mods/Essentials/data/banned.json").readString();
                JSONTokener parser = new JSONTokener(db);
                JSONArray object = new JSONArray(parser);

                LocalDateTime now = LocalDateTime.now();
                DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd a hh:mm.ss", Locale.ENGLISH);
                String myTime = now.format(dateTimeFormatter);

                for(int i = 0; i < object.length(); i++) {
                    JSONObject value1 = object.getJSONObject(i);
                    String date = (String) value1.get("date");
                    String uuid = (String) value1.get("uuid");
                    String name = (String) value1.get("name");

                    if (date.equals(myTime)) {
                        Log.info(myTime);
                        object.remove(i);
                        Core.settings.getDataDirectory().child("mods/Essentials/data/banned.json").writeString(String.valueOf(object));
                        netServer.admins.unbanPlayerID(uuid);
                        Global.log("["+myTime+"] [Bantime]"+name+"/"+uuid+" player unbanned!");
                    }
                }
            }catch (Exception ex){
                printStackTrace(ex);
            }
        }
    }
    static class maptime extends Thread {
        @Override
        public void run(){
            if(playtime != null){
                try{
                    Calendar cal1;
                    SimpleDateFormat format = new SimpleDateFormat("HH:mm.ss");
                    Date d2 = format.parse(playtime);
                    cal1 = Calendar.getInstance();
                    cal1.setTime(d2);
                    cal1.add(Calendar.SECOND, 1);
                    playtime = format.format(cal1.getTime());
                    // Anti PvP rushing timer
                    if(config.isEnableantirush() && Vars.state.rules.pvp && cal1.after(config.getAntirushtime()) && peacetime) {
                        state.rules.playerDamageMultiplier = 1f;
                        state.rules.playerHealthMultiplier = 1f;
                        peacetime = false;
                        for(int i = 0; i < playerGroup.size(); i++) {
                            Player player = playerGroup.all().get(i);
                            player.sendMessage(bundle("pvp-peacetime"));
                            Call.onPlayerDeath(player);
                        }
                    }
                }catch (Exception e){
                    printStackTrace(e);
                }
            }
        }
    }
    static class uptime extends Thread {
        @Override
        public void run(){
            if(uptime != null){
                try{
                    Calendar cal1;
                    SimpleDateFormat format = new SimpleDateFormat("HH:mm.ss");
                    Date d2 = format.parse(uptime);
                    cal1 = Calendar.getInstance();
                    cal1.setTime(d2);
                    cal1.add(Calendar.SECOND, 1);
                    uptime = format.format(cal1.getTime());
                }catch (Exception e){
                    printStackTrace(e);
                }
            }
        }
    }
    static class jumpzone extends Thread {
        @Override
        public void run(){
            if (playerGroup.size() > 0) {
                for (int i=0;i<jumpzone.length();i++) {
                    String jumpdata = jumpzone.getString(i);
                    if (jumpdata.equals("")) return;
                    String[] data = jumpdata.split("/");
                    int startx = Integer.parseInt(data[0]);
                    int starty = Integer.parseInt(data[1]);
                    int tilex = Integer.parseInt(data[2]);
                    int tiley = Integer.parseInt(data[3]);
                    String serverip = data[4];
                    int serverport = Integer.parseInt(data[5]);
                    int block = Integer.parseInt(data[6]);

                    Block target;
                    switch (block) {
                        case 1:
                        default:
                            target = Blocks.metalFloor;
                            break;
                        case 2:
                            target = Blocks.metalFloor2;
                            break;
                        case 3:
                            target = Blocks.metalFloor3;
                            break;
                        case 4:
                            target = Blocks.metalFloor5;
                            break;
                        case 5:
                            target = Blocks.metalFloorDamaged;
                            break;
                    }

                    if (!world.tile(startx, starty).block().name.matches(".*metal.*")) {
                        int size = tilex - startx;
                        for(int x = 0; x < size; x++) {
                            for(int y = 0; y < size; y++) {
                                Tile tile = world.tile(startx+x, starty+y);
                                Call.onConstructFinish(tile, target, 0, (byte) 0, Team.sharded, false);
                            }
                        }
                    }

                    for(int ix = 0; ix < playerGroup.size(); ix++) {
                        Player player = playerGroup.all().get(ix);
                        if (player.tileX() > startx && player.tileX() < tilex) {
                            if (player.tileY() > starty && player.tileY() < tiley) {
                                Call.onConnect(player.con, serverip, serverport);
                            }
                        }
                    }
                }
            }
        }
    }
    public static class checkgrief extends Thread {
        Player player;

        int routercount;
        int breakcount;
        int conveyorcount;
        int impcount;

        int routerlimit;
        int breaklimit;
        int conveyorlimit;
        int implimit;

        ArrayList<Block> impblock = new ArrayList<>();
        ArrayList<Block> block = new ArrayList<>();

        public checkgrief(Player player){
            this.player = player;
            new Thread(this).start();
        }

        @Override
        public void run() {
            // 중요 건물 추가
            impblock.add(Blocks.thoriumReactor);
            impblock.add(Blocks.impactReactor);
            impblock.add(Blocks.blastDrill);
            impblock.add(Blocks.siliconSmelter);
            impblock.add(Blocks.cryofluidMixer);
            impblock.add(Blocks.oilExtractor);
            impblock.add(Blocks.spectre);
            impblock.add(Blocks.meltdown);
            impblock.add(Blocks.turbineGenerator);

            // 일반 블록 추가
            block.add(Blocks.phaseConduit);

            // 기본값 설정
            routercount = 0;
            breakcount = 0;
            conveyorcount = 0;
            impcount = 0;

            // 최대값 설정 (레벨비례)
            int level = getData(player.uuid).getInt("level");
            routerlimit = 10 + (level * 2);
            implimit = 5 + (level * 2);
            breaklimit = 30 + (level * 3);
            conveyorlimit = 20 + (level * 3);

            // 블럭 파괴 카운트
            Events.on(BuildSelectEvent.class, e -> {
                // Nulldustry
                if (e.builder instanceof Player && e.builder.buildRequest() != null && !e.builder.buildRequest().block.name.matches(".*build.*")) {
                    if (e.breaking) {
                        // 그냥 빠른파괴
                        breakcount++;
                        if(breakcount > breaklimit){
                            for (int i = 0; i < playerGroup.size(); i++) {
                                Player other = playerGroup.all().get(i);
                                other.sendMessage(bundle(other, "grief-fast-destroy", ((Player) e.builder).name));
                            }
                        }
                        if(breakcount > breaklimit + 15){
                            Call.onKick(((Player) e.builder).con, nbundle("grief-detect-kick"));
                            for (int i = 0; i < playerGroup.size(); i++) {
                                Player other = playerGroup.all().get(i);
                                other.sendMessage(bundle(other, "grief-detect", ((Player) e.builder).name));
                            }
                        }

                        // 중요 건물
                        for (Block value : impblock) {
                            if (e.builder.buildRequest().block == value) {
                                implimit++;
                                if (impcount > implimit) {
                                    for (int i = 0; i < playerGroup.size(); i++) {
                                        Player other = playerGroup.all().get(i);
                                        other.sendMessage(bundle(other, "grief-fast-imp", ((Player) e.builder).name));
                                    }
                                }
                            }
                            if(impcount > impcount + 4){
                                Call.onKick(((Player) e.builder).con, nbundle("grief-detect-kick"));
                                for (int i = 0; i < playerGroup.size(); i++) {
                                    Player other = playerGroup.all().get(i);
                                    other.sendMessage(bundle(other, "grief-detect", ((Player) e.builder).name));
                                }
                            }
                        }

                        // 컨베이어
                        if (e.builder.buildRequest().block == Blocks.conveyor || e.builder.buildRequest().block == Blocks.titaniumConveyor) {
                            conveyorcount++;
                            if (conveyorcount > conveyorlimit) {
                                for (int i = 0; i < playerGroup.size(); i++) {
                                    Player other = playerGroup.all().get(i);
                                    other.sendMessage(bundle(other, "grief-fast-conveyor", ((Player) e.builder).name));
                                }
                            }
                            if(conveyorcount > conveyorcount + 10){
                                Call.onKick(((Player) e.builder).con, nbundle("grief-detect-kick"));
                                for (int i = 0; i < playerGroup.size(); i++) {
                                    Player other = playerGroup.all().get(i);
                                    other.sendMessage(bundle(other, "grief-detect", ((Player) e.builder).name));
                                }
                            }
                        }
                    }
                }
            });

            // Place count
            Events.on(BlockBuildEndEvent.class, e -> {
                if (!e.breaking && e.player != null && e.player.buildRequest() != null && !state.teams.get(e.player.getTeam()).cores.isEmpty()) {
                    if (e.player.buildRequest().block == Blocks.router) {
                        routercount++;
                        if (routercount > routerlimit) {
                            for (int i = 0; i < playerGroup.size(); i++) {
                                Player other = playerGroup.all().get(i);
                                other.sendMessage(bundle(other, "grief-fast-router", e.player.name));
                            }
                            Call.sendMessage("[scarlet]ALERT! " + e.player.name + "[white] player is spamming [gray]router[]!");
                        }
                        if(routercount > routerlimit + 20){
                            Call.onKick(e.player.con, nbundle("grief-detect-kick"));
                            for (int i = 0; i < playerGroup.size(); i++) {
                                Player other = playerGroup.all().get(i);
                                other.sendMessage(bundle(other, "grief-detect", e.player.name));
                            }
                        }
                    }
                }
            });
            TimerTask timer = new TimerTask() {
                @Override
                public void run() {
                    routercount = 0;
                    breakcount = 0;
                    conveyorcount = 0;
                    impcount = 0;
                }
            };
            Timer timer1 = new Timer(true);
            timer1.scheduleAtFixedRate(timer, 20000, 20000);
            if(player == null){
                timer1.cancel();
                this.interrupt();
            }
        }
    }
    static class checkthorium extends Thread {
        public Tile getNear(Tile tile, int count){
            int x = tile.x;
            int y = tile.y;
            Tile result;
            switch(count){
                case 0:
                    result = world.tile(x-1,y+2);
                    break;
                case 1:
                    result = world.tile(x,y+2);
                    break;
                case 2:
                    result = world.tile(x+1,y+2);
                    break;
                case 3:
                    result = world.tile(x+2,y+1);
                    break;
                case 4:
                    result = world.tile(x+2,y);
                    break;
                case 5:
                    result = world.tile(x+2,y-1);
                    break;
                case 6:
                    result = world.tile(x-1,y-2);
                    break;
                case 7:
                    result = world.tile(x,y-2);
                    break;
                case 8:
                    result = world.tile(x+1,y-2);
                    break;
                case 9:
                    result = world.tile(x-2,y-1);
                    break;
                case 10:
                    result = world.tile(x-2,y);
                    break;
                case 11:
                    result = world.tile(x-2,y+1);
                    break;
                default:
                    result = tile;
            }
            return result;
        }

        @Override
        public void run() {
            for (int a = 0; a < nukeposition.length(); a++) {
                String nukedata = nukeposition.getString(a);
                String[] data = nukedata.split("/");
                int x = Integer.parseInt(data[0]);
                int y = Integer.parseInt(data[1]);
                Tile tile = world.tile(x, y);

                ArrayList<Tile> open = new ArrayList<>();
                ArrayList<Tile> close = new ArrayList<>();

                boolean success;

                if (world.tile(x, y).block() != Blocks.thoriumReactor) {
                    nukeposition.remove(a);
                    return;
                }
                // 12면을 검색함
                Global.log("SEARCH START");
                int count = 0;
                for (int b = 0; b < 12; b++) {
                    open.add(getNear(tile, b));
                }
                for(int b=0;b<open.size();b++){
                    Tile target = open.get(b);
                    if(target.block() == Blocks.air){
                        open.remove(b);
                        break;
                    }
                    for(int c=0;c<4;c++){
                        if(target.getNearby(c).block() == Blocks.conduit || target.getNearby(c).block() == Blocks.pulseConduit){
                            open.add(target.getNearby(c));
                        } else if (target.getNearby(c).block() == Blocks.cryofluidMixer) {

                        }
                    }
                    // 파이프의 4면을 검색함
                    while (count < 10) {
                        for (int c = 0; c < 4; c++) {
                            Global.log(target.x+"/"+target.y);
                            // 파이프를 발견했다면
                            if (target.getNearby(c).block() == Blocks.conduit || target.getNearby(c).block() == Blocks.pulseConduit) {
                                target = target.getNearby(c);
                            } else if (target.getNearby(c).block() == Blocks.cryofluidMixer) {
                                Global.log("냉각수 공장 발견");
                                count = 100;
                            }
                        }
                        count++;
                        //Global.log(count + " 번째 " + target.x + "/" + target.y);
                    }
                }
            }
        }
    }
    static class login extends TimerTask{
        @Override
        public void run() {
            Thread.currentThread().setName("Login alert thread");
            if (playerGroup.size() > 0) {
                for(int i = 0; i < playerGroup.size(); i++) {
                    Player player = playerGroup.all().get(i);
                    if (Vars.state.teams.get(player.getTeam()).cores.isEmpty()) {
                        String message1 = "You will need to login with [accent]/login <username> <password>[] to get access to the server.\n" +
                                "If you don't have an account, use the command [accent]/register <username> <password> <password repeat>[].";
                        String message2 = "서버를 플레이 할려면 [accent]/login <사용자 이름> <비밀번호>[] 를 입력해야 합니다.\n" +
                                "만약 계정이 없다면 [accent]/register <사용자 이름> <비밀번호> <비밀번호 재입력>[]를 입력해야 합니다.";
                        player.sendMessage(message1);
                        player.sendMessage(message2);
                    }
                }
            }
        }
    }
    static class jumpcheck extends Thread {
        // Source from Anuken/CoreBot
        @Override
        public void run() {

            for (int i=0;i<jumpcount.length();i++) {
                String jumpdata = jumpcount.getString(i);
                String[] data = jumpdata.split("/");
                String serverip = data[0];
                int port = Integer.parseInt(data[1]);
                int x = Integer.parseInt(data[2]);
                int y = Integer.parseInt(data[3]);
                String count = data[4];
                int length = Integer.parseInt(data[5]);

                int finalI = i;
                pingServer(serverip, port, result -> {
                    if (result.valid) {
                        String str = result.players;
                        int[] digits = new int[str.length()];
                        for(int a = 0; a < str.length(); a++) digits[a] = str.charAt(a) - '0';

                        Tile tile = world.tile(x, y);
                        if(!count.equals(result.players)) {
                            if(length != digits.length){
                                for(int px=0;px<3;px++){
                                    for(int py=0;py<5;py++){
                                        Call.onDeconstructFinish(world.tile(tile.x+4+px,tile.y+py), Blocks.air, 0);
                                    }
                                }
                            }
                            for (int digit : digits) {
                                setcount(tile, digit);
                                tile = world.tile(tile.x+4, tile.y);
                            }
                        } else {
                            for(int l=0;l<length;l++) {
                                setcount(tile, digits[l]);
                                tile = world.tile(x + 4, y);
                            }
                        }
                        // i 번째 server ip, 포트, x좌표, y좌표, 플레이어 인원, 플레이어 인원 길이
                        jumpcount.put(finalI, serverip + "/" + port + "/" + x + "/" + y + "/" + result.players + "/" + digits.length);
                    } else {
                        setno(world.tile(x, y));
                    }
                });
            }
        }
    }
    static class jumpall extends Thread {
        @Override
        public void run() {
            for (int i=0;i<jumpall.length();i++) {
                String jumpdata = jumpall.getString(i);
                String[] data = jumpdata.split("/");
                int x = Integer.parseInt(data[0]);
                int y = Integer.parseInt(data[1]);
                int count = Integer.parseInt(data[2]);
                int length = Integer.parseInt(data[3]);

                int result = 0;
                for (int l=0;l<jumpcount.length();l++) {
                    String dat = jumpcount.getString(l);
                    String[] re = dat.split("/");
                    result += Integer.parseInt(re[4]);
                }

                String str = String.valueOf(result);
                int[] digits = new int[str.length()];
                for(int a = 0; a < str.length(); a++) digits[a] = str.charAt(a) - '0';

                Tile tile = world.tile(x, y);
                if(count != result) {
                    if(length != digits.length){
                        for(int px=0;px<3;px++){
                            for(int py=0;py<5;py++){
                                Call.onDeconstructFinish(world.tile(tile.x+4+px,tile.y+py), Blocks.air, 0);
                            }
                        }
                    }
                    for (int digit : digits) {
                        setcount(tile, digit);
                        tile = world.tile(tile.x+4, tile.y);
                    }
                } else {
                    for(int l=0;l<length;l++) {
                        setcount(tile, digits[l]);
                        tile = world.tile(x+4, y);
                    }
                }
                jumpall.put(i, x+"/"+y+"/"+result+"/"+digits.length);
            }
        }
    }
    static class changename extends Thread {
        @Override
        public void run(){
            if(jumpcount.length() > 1){
                int result = 0;
                for (int l=0;l<jumpcount.length();l++) {
                    String dat = jumpcount.getString(l);
                    String[] re = dat.split("/");
                    result += Integer.parseInt(re[4]);
                }
                String temp1 = Core.settings.getDataDirectory().child("mods/Essentials/data/data.json").readString();
                JSONTokener temp2 = new JSONTokener(temp1);
                JSONObject data = new JSONObject(temp2);

                //Core.settings.put("servername", data.getString("servername")+", "+result+" players");
                Core.settings.put("servername", config.getServername()+", "+result+" players");
            }
        }
    }
    public static class AutoRollback extends TimerTask {
        private boolean save() {
            try {
                FileHandle file = saveDirectory.child(config.getSlotnumber() + "." + saveExtension);
                SaveIO.save(file);
                return true;
            } catch (Exception e) {
                printStackTrace(e);
                return false;
            }
        }

        public void load() {
            Array<Player> all = Vars.playerGroup.all();
            Array<Player> players = new Array<>();
            players.addAll(all);

            try {
                FileHandle file = saveDirectory.child(config.getSlotnumber() + "." + saveExtension);
                SaveIO.load(file);
            } catch (SaveIO.SaveException e) {
                printStackTrace(e);
            }

            Call.onWorldDataBegin();

            for (Player p : players) {
                Vars.netServer.sendWorldData(p);
                p.reset();

                if (Vars.state.rules.pvp) {
                    p.setTeam(Vars.netServer.assignTeam(p, new Array.ArrayIterable<>(players)));
                }
            }
            Global.log("Map rollbacked.");
            Call.sendMessage("[green]Map rollbacked.");
        }

        @Override
        public void run() {
            if (save()) {
                Call.sendMessage("[scarlet]AutoSave complete");
            } else {
                Global.loge("Map save failed! Check your disk or config!");
            }
        }
    }
    static class eventserver extends Thread {
        String roomname;
        String map;
        String gamemode;
        int customport;

        @Override
        public void run() {
            try {
                FileUtils.copyURLToFile(new URL("https://github.com/Anuken/Mindustry/releases/download/v99/server-release.jar"), new File(Paths.get("").toAbsolutePath().toString()+"/config/mods/Essentials/temp/"+roomname+"/server.jar"));
                Service service = new Service(roomname, map, gamemode, customport);
                service.start();
                Thread.sleep(2000);
            } catch (Exception e) {
                printStackTrace(e);
            }
        }

        public static class Service extends Thread {
            String roomname;
            String map;
            String gamemode;
            int customport;
            int disablecount;

            Service(String roomname, String map, String gamemode, int customport) {
                this.gamemode = gamemode;
                this.map = map;
                this.roomname = roomname;
                this.customport = customport;
            }

            @Override
            public void run(){
                try {
                    Process p;
                    ProcessBuilder pb;
                    if(gamemode.equals("wave")){
                        pb = new ProcessBuilder("java", "-jar", Paths.get("").toAbsolutePath().toString() + "/config/mods/Essentials/temp/" + roomname + "/server.jar", "port", String.valueOf(customport), ",host", map);
                    } else {
                        pb = new ProcessBuilder("java", "-jar", Paths.get("").toAbsolutePath().toString() + "/config/mods/Essentials/temp/" + roomname + "/server.jar", "port", String.valueOf(customport), ",host", map, gamemode);
                    }
                    pb.directory(new File(Paths.get("").toAbsolutePath().toString() + "/config/mods/Essentials/temp/" + roomname));
                    p = pb.start();
                    process.add(p);

                    Process finalP = p;
                    TimerTask t = new TimerTask() {
                        @Override
                        public void run() {
                            pingServer("localhost", customport, result -> {
                                if (disablecount > 300) {
                                    finalP.destroy();
                                    process.remove(finalP);
                                    this.cancel();
                                } else if (result.players.contains("0")) {
                                    disablecount++;
                                }
                            });
                        }
                    };
                    Timer timer = new Timer(true);
                    timer.scheduleAtFixedRate(t, 1000, 1000);

                    Core.app.addListener(new ApplicationListener(){
                        @Override
                        public void dispose(){
                            timer.cancel();
                        }
                    });
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
    public static class ColorNick {
        private static int colorOffset = 0;
        private static long updateIntervalMs = config.getCupdatei();

        public void main(Player player){
            Thread thread = new Thread(() -> {
                Thread.currentThread().setName("Color nickname thread");
                JSONObject db = PlayerDB.getData(player.uuid);
                boolean connected = db.getBoolean("connected");
                while (connected) {
                    connected = PlayerDB.getData(player.uuid).getBoolean("connected");
                    String name = db.getString("name").replaceAll("\\[(.*?)]", "");
                    try {
                        Thread.sleep(updateIntervalMs);
                        nickcolor(name, player);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            executorService.execute(thread);
        }

        private void nickcolor(String name, Player player) {
            StringBuilder stringBuilder = new StringBuilder();

            String[] colors = new String[11];
            colors[0] = "[#ff0000]";
            colors[1] = "[#ff7f00]";
            colors[2] = "[#ffff00]";
            colors[3] = "[#7fff00]";
            colors[4] = "[#00ff00]";
            colors[5] = "[#00ff7f]";
            colors[6] = "[#00ffff]";
            colors[7] = "[#007fff]";
            colors[8] = "[#0000ff]";
            colors[9] = "[#8000ff]";
            colors[10] = "[#ff00ff]";

            String[] newnick = new String[name.length()];
            for (int i = 0; i<name.length(); i++) {
                char c = name.charAt(i);
                int colorIndex = (i+colorOffset)%colors.length;
                if (colorIndex < 0) {
                    colorIndex += colors.length;
                }
                String newtext = colors[colorIndex]+c;
                newnick[i]=newtext;
            }
            colorOffset--;
            for (String s : newnick) {
                stringBuilder.append(s);
            }
            player.name = stringBuilder.toString();
        }
    }
    static class monitorresource extends Thread {
        Array<Integer> pre = new Array<>();
        Array<Integer> cur = new Array<>();
        Array<Item> name = new Array<>();

        @Override
        public void run(){
            for (Item item : content.items()) {
                if (item.type == ItemType.material) {
                    pre.add(state.teams.get(Team.sharded).cores.first().entity.items.get(item));
                }
            }

            for (Item item : content.items()) {
                if (item.type == ItemType.material) {
                    name.add(item);
                }
            }

            try {
                Thread.sleep(750);
            } catch (InterruptedException ignored) {}

            int a=0;
            for (Item item : content.items()) {
                if (item.type == ItemType.material) {
                    int resource;
                    if(state.teams.get(Team.sharded).cores.first().entity.items.has(item)){
                        resource = state.teams.get(Team.sharded).cores.first().entity.items.get(item);
                    } else {
                        return;
                    }
                    int temp = resource - pre.get(a);
                    if(temp <= -50){
                        StringBuilder using = new StringBuilder();
                        for(int b=0;b<playerGroup.size();b++) {
                            Player p = playerGroup.all().get(b);
                            if(p.buildRequest().block.requirements == null) return;
                            for(int c=0;c<p.buildRequest().block.requirements.length;c++){
                                Item ad = p.buildRequest().block.requirements[c].item;
                                if(ad == name.get(a)){
                                    using.append(p.name).append(", ");
                                }
                            }
                        }
                        Call.sendMessage(name.get(a).name+" Resource usage is so fast!");
                        Call.sendMessage("Resource "+name.get(a).name+" is using by "+using.substring(0,using.length()-2));
                    }
                    cur.add(a, state.teams.get(Team.sharded).cores.first().entity.items.get(item));
                    a++;
                }
            }

            for (Item item : content.items()) {
                if (item.type == ItemType.material) {
                    pre.add(state.teams.get(Team.sharded).cores.first().entity.items.get(item));
                }
            }
        }
    }
    public static class Vote{
        private Player player;
        private Player target;
        private String type;
        static boolean isvoting;
        static ArrayList<String> list = new ArrayList<>();
        static int require;

        Vote(Player player, String type, String target){
            this.player = player;
            if(target != null){
                Player other = Vars.playerGroup.find(p -> p.name.equalsIgnoreCase(target));
                if(other != null){
                    this.target = other;
                }
            }
            this.type = type;
            main();
        }

        public void main(){
            if(playerGroup.size() <= 3){
                player.sendMessage(bundle(player, "vote-min"));
                return;
            }
            require = (int) Math.ceil((double) playerGroup.size() / 3);

            switch(type) {
                case "gameover":
                    if(!isvoting){
                        isvoting = true;
                        for(Player others : playerGroup.all()){
                            if(getData(others.uuid).toString().equals("{}")) return;
                            others.sendMessage(bundle(others, "vote-gameover"));
                        }
                        gameover.start();
                    } else {
                        player.sendMessage(bundle(player, "vote-in-processing"));
                    }
                    break;
                case "skipwave":
                    if(!isvoting){
                        isvoting = true;
                        for(Player others : playerGroup.all()){
                            others.sendMessage(bundle(others, "vote-skipwave"));
                        }
                        skipwave.start();
                    } else {
                        player.sendMessage(bundle(player, "vote-in-processing"));
                    }
                    break;
                case "kick":
                    if(!isvoting){
                        isvoting = true;
                        for(Player others : playerGroup.all()){
                            others.sendMessage(bundle(others, "vote-kick"));
                        }
                        kick.start();
                    } else {
                        player.sendMessage(bundle(player, "vote-in-processing"));
                    }
                    break;
                case "rollback":
                    if(!isvoting){
                        isvoting = true;
                        for(Player others : playerGroup.all()){
                            others.sendMessage(bundle(others, "vote-rollback"));
                        }
                        rollback.start();
                    } else {
                        player.sendMessage(bundle(player, "vote-in-processing"));
                    }
                    break;
                default:
                    break;
            }
        }

        static Thread counting = new Thread(() -> {
            try {
                if (playerGroup != null && playerGroup.size() > 0) {
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        others.sendMessage(bundle(others, "vote-50sec"));
                    }
                    Thread.sleep(10000);
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        others.sendMessage(bundle(others, "vote-40sec"));
                    }
                    Thread.sleep(10000);
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        others.sendMessage(bundle(others, "vote-30sec"));
                    }
                    Thread.sleep(10000);
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        others.sendMessage(bundle(others, "vote-20sec"));
                    }
                    Thread.sleep(10000);
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        others.sendMessage(bundle(others, "vote-10sec"));
                    }
                    Thread.sleep(10000);
                }
            } catch (InterruptedException e) {
                printStackTrace(e);
            }
        });
        Thread gameover = new Thread(() -> {
            counting.start();
            try {
                counting.join();
            } catch (InterruptedException e) {
                printStackTrace(e);
            }
            if (list.size() >= require && isvoting) {
                Call.sendMessage("[green][Essentials] Gameover vote passed!");
                Events.fire(new EventType.GameOverEvent(Team.sharded));
            } else {
                Call.sendMessage("[green][Essentials] [red]Gameover vote failed.");
            }
            list.clear();
            isvoting = false;
        });

        Thread skipwave = new Thread(() -> {
            counting.start();
            try {
                counting.join();
            } catch (InterruptedException e) {
                printStackTrace(e);
            }
            if (list.size() >= require && isvoting) {
                Call.sendMessage("[green][Essentials] Skip 10 wave vote passed!");
                for (int i = 0; i < 10; i++) {
                    logic.runWave();
                }
            } else {
                Call.sendMessage("[green][Essentials] [red]Skip 10 wave vote failed.");
            }
            list.clear();
            isvoting = false;
        });

        Thread kick = new Thread(() -> {
            if(target != null){
                counting.start();
                try {
                    counting.join();
                } catch (InterruptedException e) {
                    printStackTrace(e);
                }
                if (list.size() >= require && isvoting) {
                    Call.sendMessage("[green][Essentials] Player kick vote success!");
                    PlayerDB.addtimeban(target.name, target.uuid, 4);
                    Global.log(target.name + " / " + target.uuid + " Player has banned due to voting. " + list.size() + "/" + require);

                    Path path = Paths.get(String.valueOf(Core.settings.getDataDirectory().child("mods/Essentials/Logs/Player.log")));
                    Path total = Paths.get(String.valueOf(Core.settings.getDataDirectory().child("mods/Essentials/Logs/Total.log")));
                    try {
                        JSONObject other = getData(target.uuid);
                        String text = other.get("name") + " / " + target.uuid + " Player has banned due to voting. " + list.size() + "/" + require + "\n";
                        byte[] result = text.getBytes();
                        Files.write(path, result, StandardOpenOption.APPEND);
                        Files.write(total, result, StandardOpenOption.APPEND);
                    } catch (IOException error) {
                        printStackTrace(error);
                    }

                    netServer.admins.banPlayer(target.uuid);
                    Call.onKick(target.con, "You're banned.");
                } else {
                    for (int i = 0; i < playerGroup.size(); i++) {
                        Player others = playerGroup.all().get(i);
                        bundle(others, "vote-failed");
                    }
                }
                list.clear();
                isvoting = false;
            } else {
                bundle(player, "player-not-found");
            }
        });

        Thread rollback = new Thread(() -> {
            counting.start();
            try {
                counting.join();
            } catch (InterruptedException e) {
                printStackTrace(e);
            }
            if (list.size() >= require && isvoting) {
                Call.sendMessage("[green][Essentials] Map rollback passed!!");
                Threads.AutoRollback rl = new Threads.AutoRollback();
                rl.load();
            } else {
                Call.sendMessage("[green][Essentials] [red]Map rollback failed.");
            }
            list.clear();
            isvoting = false;
        });
    }
    public static class votecheck{
        static boolean isvoting = Vote.isvoting;
        static ArrayList<String> list = Vote.list;
        static int require = Vote.require;

        public void main(){
            if(Vote.counting.isAlive()){
                Global.log("interrupted");
                Vote.counting.interrupt();
            }
        }
    }
    public static class getip extends Thread{
        private volatile String value;

        @Override
        public void run(){
            try{
                URL whatismyip = new URL("http://checkip.amazonaws.com");
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        whatismyip.openStream()));

                value = in.readLine();
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        public String getValue(){
            return value;
        }
    }
}