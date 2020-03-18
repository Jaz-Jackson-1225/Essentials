package essentials.special;

import arc.Core;
import arc.files.Fi;
import essentials.PluginData;
import org.hjson.JsonObject;
import org.hjson.JsonValue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

import static essentials.Global.locale;
import static essentials.Global.nbundle;
import static essentials.Main.config;
import static essentials.Main.pluginData;
import static essentials.core.PlayerDB.conn;

public class DataMigration {
    Fi root = Core.settings.getDataDirectory().child("mods/Essentials/");

    public void ConvertFile(){
        if(root.child("config.yml").exists()) {
            System.out.print("\r"+nbundle(locale,"data-migration"));
            String condata = root.child("config.yml").readString();
            condata = condata.replace("colornick update interval","cupdatei").replace("null","none");
            root.child("config.hjson").writeString("{\n"+condata+"\n}");
            root.child("config.yml").delete();

            if (root.child("BlockReqExp.yml").exists()) move("BlockReqExp");
            if (root.child("Exp.yml").exists()) move("Exp");
            if (root.child("permission.yml").exists()) {
                root.child("permission.yml").delete();
                config.extract();
            }
            if (root.child("data/data.json").exists()) {
                String json = root.child("data/data.json").readString();
                JsonObject value = JsonValue.readJSON(json).asObject();
                if(value.get("banned") != null) {
                    JsonObject arrays = value.get("banned").asObject();
                    pluginData.saveall();
                    for (int a = 0; a < arrays.size(); a++) {
                        LocalDateTime date = LocalDateTime.parse(arrays.get("date").asString());
                        String name = arrays.get("name").asString();
                        String uuid = arrays.get("uuid").asString();
                        String reason = arrays.get("reason").asString();
                        pluginData.banned.add(new PluginData.banned(date, name, uuid, reason));
                    }
                    // 서버간 이동 데이터들은 변환하지 않음
                    root.child("data/data.json").delete();
                }
            }
            System.out.print("\r"+nbundle(locale,"data-migration")+" "+nbundle(locale,"success")+"\n");
        }
    }

    public void MigrateDB() {
        try {
            String stringbuf = nbundle(locale,"db-migration")+" ";
            System.out.print("\r"+stringbuf);

            String sql = "INSERT INTO players VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            conn.prepareStatement("DELETE FROM players").execute();
            PreparedStatement pstmt = conn.prepareStatement(sql);

            Connection con = DriverManager.getConnection(config.getOldDBURL(), config.getOldDBID(), config.getOldDBPW());
            PreparedStatement pmt = con.prepareStatement("SELECT * from players");
            ResultSet rs = pmt.executeQuery();

            int total = 0;
            if (rs.last()) {
                total = rs.getRow();
                rs.beforeFirst();
            }
            int current = 0;

            while (rs.next()) {
                pstmt.setString(1, rs.getString("name"));
                pstmt.setString(2, rs.getString("uuid"));
                pstmt.setString(3, rs.getString("country"));
                pstmt.setString(4, rs.getString("country_code"));
                pstmt.setString(5, rs.getString("language"));
                pstmt.setBoolean(6, rs.getBoolean("isadmin"));
                pstmt.setInt(7, rs.getInt("placecount"));
                pstmt.setInt(8, rs.getInt("breakcount"));
                pstmt.setInt(9, rs.getInt("killcount"));
                pstmt.setInt(10, rs.getInt("deathcount"));
                pstmt.setInt(11, rs.getInt("joincount"));
                pstmt.setInt(12, rs.getInt("kickcount"));
                pstmt.setInt(13, rs.getInt("level"));
                pstmt.setInt(14, rs.getInt("exp"));
                pstmt.setInt(15, rs.getInt("reqexp"));
                pstmt.setString(16, rs.getString("reqtotalexp"));
                pstmt.setString(17, rs.getString("firstdate"));
                pstmt.setString(18, rs.getString("lastdate"));
                pstmt.setString(19, rs.getString("lastplacename"));
                pstmt.setString(20, rs.getString("lastbreakname"));
                pstmt.setString(21, rs.getString("lastchat"));
                pstmt.setString(22, rs.getString("playtime"));
                pstmt.setInt(23, rs.getInt("attackclear"));
                pstmt.setInt(24, rs.getInt("pvpwincount"));
                pstmt.setInt(25, rs.getInt("pvplosecount"));
                pstmt.setInt(26, rs.getInt("pvpbreakout"));
                pstmt.setInt(27, rs.getInt("reactorcount"));
                pstmt.setInt(28, rs.getInt("bantimeset"));
                pstmt.setString(29, rs.getString("bantime"));
                pstmt.setBoolean(30, rs.getBoolean("banned"));
                pstmt.setBoolean(31, rs.getBoolean("translate"));
                pstmt.setBoolean(32, rs.getBoolean("crosschat"));
                pstmt.setBoolean(33, rs.getBoolean("colornick"));
                pstmt.setBoolean(34, rs.getBoolean("connected"));
                pstmt.setString(35, rs.getString("connserver"));
                pstmt.setString(36, rs.getString("permission"));
                pstmt.setBoolean(37, rs.getBoolean("mute"));
                pstmt.setBoolean(38, true);
                pstmt.setLong(39, rs.getLong("udid"));
                pstmt.setString(40, rs.getString("email"));
                pstmt.setString(41, rs.getString("accountid"));
                pstmt.setString(42, rs.getString("accountpw"));
                pstmt.execute();
                current++;
                System.out.print("\r"+stringbuf+current+"/"+total);
            }
            pstmt.close();
            pmt.close();
            rs.close();
            con.close();

            System.out.print("\r"+stringbuf+current+"/"+total+" "+nbundle(locale,"success")+"\n");
            config.getOldDBMigration = false;
            config.update();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void move(String path){
        String data = root.child(path+".yml").readString();
        root.child(path+".hjson").writeString("{\n"+data+"\n}");
        root.child(path+".yml").delete();
    }
}
