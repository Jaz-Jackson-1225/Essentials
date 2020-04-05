package essentials.internal.thread;

import essentials.internal.Bundle;
import essentials.internal.CrashReport;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Playerc;
import org.hjson.JsonObject;
import org.hjson.JsonValue;
import org.jsoup.Jsoup;

import java.net.UnknownHostException;
import java.util.Locale;
import java.util.TimerTask;

import static essentials.Main.*;

public class Login extends TimerTask {
    @Override
    public void run() {
        Thread.currentThread().setName("Login alert thread");
        if (Groups.player.size() > 0) {
            for (int i = 0; i < Groups.player.size(); i++) {
                Playerc player = Groups.player.index(i);
                if (Vars.state.teams.get(player.team()).cores.isEmpty()) {
                    try {
                        String message;
                        String json = Jsoup.connect("http://ipapi.co/" + Vars.netServer.admins.getInfo(player.uuid()).lastIP + "/json").ignoreContentType(true).execute().body();
                        JsonObject result = JsonValue.readJSON(json).asObject();
                        Locale language = tool.TextToLocale(result.getString("languages", locale.toString()));
                        if (config.passwordmethod.equals("discord")) {
                            message = new Bundle(language).get("login-require-discord") + "\n" + config.discordlink;
                        } else {
                            message = new Bundle(language).get("login-require-password");
                        }
                        player.sendMessage(message);
                    } catch (UnknownHostException e) {
                        Bundle bundle = new Bundle();
                        Call.onKick(player.con(), bundle.get("plugin-error-kick"));
                    } catch (Exception e) {
                        new CrashReport(e);
                    }
                }
            }
        }
    }
}
