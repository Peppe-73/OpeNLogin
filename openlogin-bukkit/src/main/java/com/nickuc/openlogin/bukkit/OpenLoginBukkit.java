/*
 * This file is part of a NickUC project
 *
 * Copyright (c) NickUC <nickuc.com>
 * https://github.com/nickuc
 */

package com.nickuc.openlogin.bukkit;

import com.nickuc.openlogin.bukkit.api.OLBukkitAPI;
import com.nickuc.openlogin.bukkit.commands.CommandManagement;
import com.nickuc.openlogin.bukkit.listeners.PlayerAuthenticateListener;
import com.nickuc.openlogin.bukkit.listeners.PlayerGeneralListeners;
import com.nickuc.openlogin.bukkit.listeners.PlayerJoinListeners;
import com.nickuc.openlogin.bukkit.listeners.PlayerKickListeners;
import com.nickuc.openlogin.bukkit.task.LoginQueue;
import com.nickuc.openlogin.common.OpenLogin;
import com.nickuc.openlogin.common.api.OpenLoginAPI;
import com.nickuc.openlogin.common.database.Database;
import com.nickuc.openlogin.common.database.SQLite;
import com.nickuc.openlogin.common.http.Http;
import com.nickuc.openlogin.common.manager.LoginManagement;
import com.nickuc.openlogin.common.model.Title;
import com.nickuc.openlogin.common.security.filter.LoggerFilterManager;
import com.nickuc.openlogin.common.settings.Messages;
import com.nickuc.openlogin.common.settings.Settings;
import com.nickuc.openlogin.common.utils.FileUtils;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

@Getter
public class OpenLoginBukkit extends JavaPlugin {

    private LoginManagement loginManagement;
    private CommandManagement commandManagement;
    private Database database;
    private String latestVersion;
    private boolean updateAvailable;

    public void onEnable() {

        PluginManager pm = getServer().getPluginManager();

        // detect nLogin
        if (pm.getPlugin("nLogin") != null) {
            sendMessage("nLogin was detected, turning off plugin...");
            pm.disablePlugin(this);
            return;
        }

        String c = "§9";
        sendMessage(c + "   ___                __  __             _ ");
        sendMessage(c + "  /___\\_ __   ___  /\\ \\ \\/ /  ___   __ _(_)_ __");
        sendMessage(c + " //  // '_ \\ / _ \\/  \\/ / /  / _ \\ / _` | | '_ \\");
        sendMessage(c + "/ \\_//| |_) |  __/ /\\  / /__| (_) | (_| | | | | |");
        sendMessage(c + "\\___/ | .__/ \\___\\_\\ \\/\\____/\\___/ \\__, |_|_| |_|");
        sendMessage(c + "      |_|                          |___/         ");
        sendMessage(c + "By: www.nickuc.com / github.com/nickuc/OpeNLogin - V " + getDescription().getVersion());
        sendMessage("");

        Server server = getServer();
        boolean newUser = !new File(getDataFolder() + "/database", "accounts.db").exists() && !new File(getDataFolder(), "config.yml").exists();

        // setup config
        if (!setupSettings()) {
            server.shutdown();
            return;
        }

        // setup database
        if (!setupDatabase()) {
            server.shutdown();
            return;
        }

        // setup login management
        loginManagement = new LoginManagement(database);

        // setup commands
        commandManagement = new CommandManagement(this);
        commandManagement.register();

        // setup logger filter
        LoggerFilterManager.setup(getLogger());

        // setup listeners
        setupListeners(newUser);

        // start login queue task
        LoginQueue.startTask(this);

        // metrics
        setupMetrics();

        // setup api
        OpenLogin.setApi(new OLBukkitAPI(this));
    }

    public void sendMessage(String message) {
        getServer().getConsoleSender().sendMessage("[" + getName() + "] " + message);
    }

    private boolean setupDatabase() {
        File databaseFile = new File(getDataFolder(), "accounts.db");
        database = new SQLite(databaseFile);
        try {
            database.openConnection();
            database.update("CREATE TABLE IF NOT EXISTS `openlogin` (`name` TEXT, `realname` TEXT, `password` TEXT, `address` TEXT, `lastlogin` TEXT, `regdate` TEXT)");
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            sendMessage("§cFailed to start database. Shutting down server...");
            return false;
        }
    }

    private void setupListeners(boolean newUser) {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerGeneralListeners(this), this);
        pm.registerEvents(new PlayerJoinListeners(this), this);
        pm.registerEvents(new PlayerKickListeners(this), this);
        pm.registerEvents(new PlayerAuthenticateListener(this, newUser), this);
    }

    private void setupMetrics() {
        Metrics metrics = new Metrics(this, 8951);
        metrics.addCustomChart(new Metrics.SimplePie("language_file", Settings.LANGUAGE_FILE::asString));
    }

    public void detectUpdates() {
        String tagName = null;
        Http http = new Http("https://api.github.com/repos/nickuc/OpeNLogin/releases/latest");
        try {
            http.get();

            String result = http.result();

            // avoid use Google Gson to avoid problems with older versions.
            if (result.contains("\"tag_name\": \"")) {
                tagName = result.split("\"tag_name\": \"")[1];
                if (tagName.contains("\",")) {
                    tagName = latestVersion = result.split("\",")[0];
                }
            }
        } catch (IOException e) {
            sendMessage("§cFailed to find new updates.");
            sendMessage("§cDownload the latest version at: https://github.com/nickuc/OpeNLogin/releases");
        }
        if (tagName == null) {
            sendMessage("§cFailed to find new updates: invalid response.");
            sendMessage("§cDownload the latest version at: https://github.com/nickuc/OpeNLogin/releases");
        } else {
            String currentVersion = "v" + getDescription().getVersion();
            updateAvailable = !currentVersion.equals(tagName);
        }
    }

    public boolean setupSettings() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists() && !FileUtils.copyFromJar("com/nickuc/openlogin/config/config.yml", configFile)) {
            sendMessage("§cFailed to create 'config.yml' file.");
            return false;
        }

        Settings.clear();
        for (Settings setting : Settings.values()) {
            Settings.define(setting, getConfig().get(setting.getKey()));
        }

        String lang = Settings.LANGUAGE_FILE.asString();
        File messagesFile = new File(getDataFolder() + "/lang", lang);
        if (!messagesFile.exists() && !FileUtils.copyFromJar("com/nickuc/openlogin/config/lang/" + lang, messagesFile) && !FileUtils.copyFromJar("com/nickuc/openlogin/config/lang/messages_en.yml", messagesFile)) {
            sendMessage("§cFailed to create '" + lang + "' language file.");
            return false;
        }

        YamlConfiguration messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        for (Messages message : Messages.values()) {
            String path = message.getKey();
            if (path.startsWith("Messages.Title")) {
                String title = "", subtitle = "";
                int start = 0, duration = 0, end = 0;

                path = path + ".";
                if (messagesConfig.isSet(path + "title") && messagesConfig.isSet(path + "subtitle")) {
                    title = messagesConfig.getString(path + "title");
                    subtitle = messagesConfig.getString(path + "subtitle");
                    start = messagesConfig.getInt(path + "delays.start", 0);
                    duration = messagesConfig.getInt(path + "delays.duration", 60);
                    end = messagesConfig.getInt(path + "delays.end", 6);
                    Messages.define(message, new Title(title, subtitle, start, duration, end));
                }
            } else if (messagesConfig.isSet(path)) {
                Object obj = messagesConfig.get(path);
                Messages.define(message, obj);
            }
        }
        return true;
    }

    public static OpenLoginAPI getApi() {
        return OpenLogin.getApi();
    }

}