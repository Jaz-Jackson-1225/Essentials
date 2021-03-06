package essentials.external;

import arc.Core;
import arc.files.Fi;
import essentials.internal.CrashReport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static essentials.Main.config;
import static essentials.Main.database;

public class DataMigration {
    Fi root = Core.settings.getDataDirectory().child("mods/Essentials/");

    public void MigrateDB() {
        String stringbuf = config.bundle.get("database.migration") + " ";
        System.out.print("\r" + stringbuf);

        String sql = "INSERT INTO players VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement del = database.conn.prepareStatement("DELETE FROM players");
             PreparedStatement pstmt = database.conn.prepareStatement(sql);

             Connection con = DriverManager.getConnection(config.oldDBurl(), config.oldDBid(), config.oldDBpw());
             PreparedStatement pmt = con.prepareStatement("SELECT * from players");
             ResultSet rs = pmt.executeQuery()) {
            del.execute();
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
                pstmt.setString(40, rs.getString("accountid"));
                pstmt.setString(41, rs.getString("accountpw"));
                pstmt.execute();
                current++;
                System.out.print("\r" + stringbuf + current);
            }
            pstmt.close();
            pmt.close();
            rs.close();
            con.close();

            System.out.print("\r" + stringbuf + current + " " + config.bundle.get("success") + "\n");

            config.oldDBMigration(false);
            config.updateConfig();
        } catch (Exception e) {
            new CrashReport(e);
        }
    }
}
