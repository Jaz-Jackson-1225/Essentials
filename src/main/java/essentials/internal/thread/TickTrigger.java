package essentials.internal.thread;

import arc.Core;
import arc.Events;
import arc.util.Strings;
import essentials.core.player.PlayerData;
import essentials.core.plugin.PluginData;
import essentials.external.PingHost;
import essentials.feature.Exp;
import essentials.internal.Bundle;
import essentials.internal.CrashReport;
import essentials.internal.Log;
import mindustry.content.Blocks;
import mindustry.core.GameState;
import mindustry.entities.type.Player;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.type.Item;
import mindustry.type.ItemType;
import mindustry.world.Tile;
import mindustry.world.blocks.logic.MessageBlock;
import org.hjson.JsonObject;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static essentials.Main.*;
import static essentials.PluginVars.*;
import static mindustry.Vars.*;
import static mindustry.core.NetClient.onSetRules;

public class TickTrigger {
    public TickTrigger() {
        Events.on(EventType.Trigger.update, new Runnable() {
            int tick = 0;
            Map<String, Integer> resources = new HashMap<>();

            @Override
            public void run() {
                if (tick < 86400) {
                    tick++;
                } else {
                    tick = 0;
                }

                if (config.border) {
                    for (Player p : playerGroup.all()) {
                        if (p.x > world.width() * 8 || p.x < 0 || p.y > world.height() * 8 || p.y < 0)
                            Call.onPlayerDeath(p);
                    }
                }

                // 1초마다 실행
                if ((tick % 60) != 0) {
                    // 서버 켜진시간 카운트
                    uptime = uptime.plusSeconds(1);

                    // 데이터 저장
                    JsonObject json = new JsonObject();
                    json.add("servername", Core.settings.getString("servername"));
                    root.child("data/data.json").writeString(json.toString());

                    // 현재 서버 이름에다가 클라이언트 서버에 대한 인원 새기기
                    // new changename().start();

                    // 임시로 밴당한 유저 감시
                    for (int a = 0; a < pluginData.banned.size(); a++) {
                        LocalDateTime time = LocalDateTime.now();
                        if (time.isAfter(pluginData.banned.get(a).getTime())) {
                            pluginData.banned.remove(a);
                            netServer.admins.unbanPlayerID(pluginData.banned.get(a).uuid);
                            Log.info("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + pluginData.banned.get(a).name + "/" + pluginData.banned.get(a).uuid + " player unbanned!");
                            break;
                        }
                    }

                    // 맵이 돌아가고 있을 때
                    if (state.is(GameState.State.playing)) {
                        // 서버간 이동 패드에 플레이어가 있는지 확인
                        // new jumpzone().start();

                        // 맵 플탐 카운트
                        playtime = playtime.plusSeconds(1);

                        // PvP 평화시간 카운트
                        if (config.antirush && state.rules.pvp && playtime.isAfter(config.antirushtime) && PvPPeace) {
                            state.rules.playerDamageMultiplier = 0.66f;
                            state.rules.playerHealthMultiplier = 0.8f;
                            onSetRules(state.rules);
                            for (Player p : playerGroup.all()) {
                                player.sendMessage(new Bundle(playerDB.get(p.uuid).locale).get("pvp-peacetime"));
                                player.kill();
                            }
                            PvPPeace = false;
                        }

                    /*if(config.debugcode.contains("jumptotal_count")){
                        int result = 0;
                        for (PluginData.jumpcount value : data.jumpcount) result = result + value.players;
                        String name = "[#FFA]Lobby server [green]|[white] Anti griefing\n" +
                                "[#F32]Using Discord Authentication";
                        String desc = "[white]"+config.discordlink+"\n" +
                                "[green]Total [white]"+result+" Players\n" +
                                "[sky]POWERED BY Essentials 9.0.0";
                        Administration.Config c = Administration.Config.desc;
                        Administration.Config s = Administration.Config.name;
                        c.set(desc);
                        s.set(name);
                    }*/

                        // 모든 클라이언트 서버에 대한 인원 총합 카운트
                        for (int a = 0; a < pluginData.jumptotal.size(); a++) {
                            int result = 0;
                            for (PluginData.jumpcount value : pluginData.jumpcount) result = result + value.players;

                            String str = String.valueOf(result);
                            int[] digits = new int[str.length()];
                            for (int b = 0; b < str.length(); b++) digits[b] = str.charAt(b) - '0';

                            Tile tile = pluginData.jumptotal.get(a).getTile();
                            if (pluginData.jumptotal.get(a).totalplayers != result) {
                                if (pluginData.jumptotal.get(a).numbersize != digits.length) {
                                    for (int px = 0; px < 3; px++) {
                                        for (int py = 0; py < 5; py++) {
                                            Call.onDeconstructFinish(world.tile(tile.x + 4 + px, tile.y + py), Blocks.air, 0);
                                        }
                                    }
                                }
                            }
                            tool.setTileText(tile, Blocks.copperWall, String.valueOf(result));

                            pluginData.jumptotal.set(a, new PluginData.jumptotal(tile, result, digits.length));
                        }

                        // 플레이어 플탐 카운트 및 잠수확인
                        for (Player p : playerGroup.all()) {
                            PlayerData target = playerDB.get(p.uuid);
                            boolean kick = false;

                            if (target.isLogin) {
                                // Exp 계산
                                target.exp(target.exp + (int) (Math.random() * 5));

                                // 잠수 및 플레이 시간 계산
                                target.playtime(LocalTime.parse(target.playtime, DateTimeFormatter.ofPattern("HH:mm:ss")).plusSeconds(1).format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                                if (target.afk_tilex == p.tileX() && target.afk_tiley == p.tileY()) {
                                    target.afk(target.afk.plusSeconds(1));
                                    if (target.afk == LocalTime.of(0, 5, 0)) {
                                        kick = true;
                                    }
                                } else {
                                    target.afk(LocalTime.of(0, 0, 0));
                                }
                                target.afk_tilex(p.tileX());
                                target.afk_tiley(p.tileY());

                                if (!state.rules.editor) new Exp(p);
                                if (kick) Call.onKick(p.con, "AFK");
                            }
                            if (target.grief_destory_count > 0)
                                target.grief_destory_count(target.grief_destory_count--);
                            if (target.grief_build_count > 0) target.grief_build_count(target.grief_build_count--);
                        }

                        // 메세지 블럭 감시
                        for (int a = 0; a < pluginData.messagemonitor.size(); a++) {
                            String msg;
                            MessageBlock.MessageBlockEntity entity;
                            try {
                                entity = (MessageBlock.MessageBlockEntity) pluginData.messagemonitor.get(a).tile.entity;
                                msg = entity.message;
                            } catch (NullPointerException e) {
                                pluginData.messagemonitor.remove(a);
                                return;
                            }

                            if (msg.equals("powerblock")) {
                                Tile target;

                                if (entity.tile.getNearby(0).entity != null) {
                                    target = entity.tile.getNearby(0);
                                } else if (entity.tile.getNearby(1).entity != null) {
                                    target = entity.tile.getNearby(1);
                                } else if (entity.tile.getNearby(2).entity != null) {
                                    target = entity.tile.getNearby(2);
                                } else if (entity.tile.getNearby(3).entity != null) {
                                    target = entity.tile.getNearby(3);
                                } else {
                                    return;
                                }
                                pluginData.powerblock.add(new PluginData.powerblock(entity.tile, target));
                                pluginData.messagemonitor.remove(a);
                                break;
                            } else if (msg.contains("jump")) {
                                pluginData.messagejump.add(new PluginData.messagejump(pluginData.messagemonitor.get(a).tile, msg));
                                pluginData.messagemonitor.remove(a);
                                break;
                            } else if (msg.equals("scancore")) {
                                pluginData.scancore.add(pluginData.messagemonitor.get(a).tile);
                                pluginData.messagemonitor.remove(a);
                                break;
                            }
                        }

                        // 서버 인원 확인
                        for (int i = 0; i < pluginData.jumpcount.size(); i++) {
                            int i2 = i;
                            PluginData.jumpcount value = pluginData.jumpcount.get(i);

                            new PingHost(value.ip, value.port, result -> {
                                if (result.name != null) {
                                    String str = String.valueOf(result.players);
                                    int[] digits = new int[str.length()];
                                    for (int a = 0; a < str.length(); a++) digits[a] = str.charAt(a) - '0';

                                    Tile tile = value.getTile();
                                    if (value.players != result.players) {
                                        if (value.numbersize != digits.length) {
                                            for (int px = 0; px < 3; px++) {
                                                for (int py = 0; py < 5; py++) {
                                                    Call.onDeconstructFinish(world.tile(tile.x + 4 + px, tile.y + py), Blocks.air, 0);
                                                }
                                            }
                                        }
                                    }
                                    tool.setTileText(tile, Blocks.copperWall, str);
                                    // i 번째 server ip, 포트, x좌표, y좌표, 플레이어 인원, 플레이어 인원 길이
                                    pluginData.jumpcount.set(i2, new PluginData.jumpcount(value.getTile(), value.ip, value.port, result.players, digits.length));
                                } else {
                                    tool.setTileText(value.getTile(), Blocks.copperWall, "no");
                                }
                            });
                        }

                        // 서버간 이동 영역에 플레이어가 있는지 확인
                        for (PluginData.jumpzone value : pluginData.jumpzone) {
                            if (!value.touch) {
                                for (int ix = 0; ix < playerGroup.size(); ix++) {
                                    Player player = playerGroup.all().get(ix);
                                    if (player.tileX() > value.startx && player.tileX() < value.finishx) {
                                        if (player.tileY() > value.starty && player.tileY() < value.finishy) {
                                            String resultIP = value.ip;
                                            int port = 6567;
                                            if (resultIP.contains(":") && Strings.canParsePostiveInt(resultIP.split(":")[1])) {
                                                String[] temp = resultIP.split(":");
                                                resultIP = temp[0];
                                                port = Integer.parseInt(temp[1]);
                                            }
                                            Log.info("player-jumped", player.name, resultIP + ":" + port);
                                            Call.onConnect(player.con, resultIP, port);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 1분마다 실행
                if ((tick % 3600) != 0) {
                    try {
                        playerDB.saveAll();
                        pluginData.saveAll();
                    } catch (Exception e) {
                        new CrashReport(e);
                    }
                }

                // 1.5초마다 실행
                if ((tick % 90) != 0) {
                    if (state.is(GameState.State.playing)) {
                        for (Item item : content.items()) {
                            if (item.type == ItemType.material) {
                                if (state.teams.get(Team.sharded).cores.isEmpty()) return;
                                if (state.teams.get(Team.sharded).cores.first().items.has(item)) {
                                    int cur = state.teams.get(Team.sharded).cores.first().items.get(item);
                                    if (resources.get(item.name) != null) {
                                        if ((cur - resources.get(item.name)) <= -55) {
                                            StringBuilder using = new StringBuilder();
                                            for (Player p : playerGroup) {
                                                if (p.buildRequest().block != null) {
                                                    for (int c = 0; c < p.buildRequest().block.requirements.length; c++) {
                                                        if (p.buildRequest().block.requirements[c].item.name.equals(item.name)) {
                                                            using.append(p.name).append(", ");
                                                        }
                                                    }
                                                }
                                            }
                                            tool.sendMessageAll("resource-fast-use", item.name, using.substring(0, using.length() - 2));
                                        }
                                    } else {
                                        resources.put(item.name, cur);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}
