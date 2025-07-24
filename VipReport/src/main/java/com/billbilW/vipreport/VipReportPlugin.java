package com.billbilW.vipreport;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.Scanner;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONObject;

public class VipReportPlugin extends JavaPlugin implements Listener {
    private final Set<UUID> vipPlayers = new HashSet<>();
    private static final List<String> VALID_REASONS = Arrays.asList(
            "作弊", "辱骂", "利用漏洞", "消极游戏"
    );
    private static final Map<String, Long> BAN_DURATIONS = Map.of(
            "作弊", 2592000000L, // 30天
            "辱骂", 86400000L,   // 1天
            "利用漏洞", 604800000L, // 7天
            "消极游戏", 3600000L    // 1小时
    );
    private static final long VIP_PROCESS_DELAY = 5 * 20L; // 5秒(20 ticks/秒)
    private static final long NORMAL_PROCESS_DELAY = 5 * 60 * 20L; // 5分钟
    private final Map<UUID, Integer> vipLevels = new HashMap<>();
    private final Map<UUID, Long> lastReportTime = new HashMap<>();

    // GitHub API URL
    private static final String GITHUB_API_URL = "https://api.github.com/repos/billbilW/Mc1.20.1-VIPandReport/releases/latest";
    private static final long UPDATE_CHECK_INTERVAL = 5 * 60 * 60 * 20L; // 5小时

    @Override
    public void onEnable() {
        // 设置信任管理器以处理SSL证书问题
        setupTrustManager();

        getCommand("report").setExecutor(this::onReportCommand);
        getCommand("vip").setExecutor(this::onVipCommand);
        setupScoreboardTeams();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(this, this);

        // 加载已有的VIP数据（如果有）
        loadVipData();

        // 更新所有在线玩家的前缀
        updateAllPlayerPrefixes();

        // 加载配置文件
        saveDefaultConfig();

        // 打印加载的版本号，用于调试
        getLogger().info("插件加载的版本号: " + getDescription().getVersion());

        // 启动检查更新任务
        startUpdateCheckTask();

        getLogger().info("VIPReport插件已启用！版本: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        // 保存VIP数据
        saveVipData();
        getLogger().info("VIPReport插件已禁用！");
    }

    private void setupScoreboardTeams() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team adminVipTeam = scoreboard.getTeam("AdminVip");
        if (adminVipTeam == null) {
            adminVipTeam = scoreboard.registerNewTeam("AdminVip");
        }
        Team adminTeam = scoreboard.getTeam("Admin");
        if (adminTeam == null) {
            adminTeam = scoreboard.registerNewTeam("Admin");
        }
        Team vipTeam = scoreboard.getTeam("VIP");
        if (vipTeam == null) {
            vipTeam = scoreboard.registerNewTeam("VIP");
        }
    }

    private void updatePlayerPrefix(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        boolean isOp = player.isOp();
        boolean isVip = vipPlayers.contains(player.getUniqueId());
        Team adminVipTeam = scoreboard.getTeam("AdminVip");
        Team adminTeam = scoreboard.getTeam("Admin");
        Team vipTeam = scoreboard.getTeam("VIP");

        if (isOp && isVip) {
            int level = vipLevels.getOrDefault(player.getUniqueId(), 1);
            if (adminVipTeam != null) {
                adminVipTeam.setPrefix("§c[Admin]§e[VIP " + level + "] ");
                adminVipTeam.addEntry(player.getName());
            }
            if (adminTeam != null) {
                adminTeam.removeEntry(player.getName());
            }
            if (vipTeam != null) {
                vipTeam.removeEntry(player.getName());
            }
        } else if (isOp) {
            if (adminTeam != null) {
                adminTeam.setPrefix("§c[Admin] ");
                adminTeam.addEntry(player.getName());
            }
            if (adminVipTeam != null) {
                adminVipTeam.removeEntry(player.getName());
            }
            if (vipTeam != null) {
                vipTeam.removeEntry(player.getName());
            }
        } else if (isVip) {
            int level = vipLevels.getOrDefault(player.getUniqueId(), 1);
            if (vipTeam != null) {
                vipTeam.setPrefix("§e[VIP " + level + "] ");
                vipTeam.addEntry(player.getName());
            }
            if (adminVipTeam != null) {
                adminVipTeam.removeEntry(player.getName());
            }
            if (adminTeam != null) {
                adminTeam.removeEntry(player.getName());
            }
        } else {
            if (adminVipTeam != null) {
                adminVipTeam.removeEntry(player.getName());
            }
            if (adminTeam != null) {
                adminTeam.removeEntry(player.getName());
            }
            if (vipTeam != null) {
                vipTeam.removeEntry(player.getName());
            }
        }
    }

    private void updateAllPlayerPrefixes() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerPrefix(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayerPrefix(player);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String format = event.getFormat();

        // 获取玩家的前缀
        String prefix = getPlayerPrefix(player);

        // 修改聊天消息格式，添加前缀
        event.setFormat(prefix + format);
    }

    private String getPlayerPrefix(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        boolean isOp = player.isOp();
        boolean isVip = vipPlayers.contains(player.getUniqueId());

        if (isOp && isVip) {
            int level = vipLevels.getOrDefault(player.getUniqueId(), 1);
            return "§c[Admin]§e[VIP " + level + "] ";
        } else if (isOp) {
            return "§c[Admin] ";
        } else if (isVip) {
            int level = vipLevels.getOrDefault(player.getUniqueId(), 1);
            return "§e[VIP " + level + "] ";
        }
        return "";
    }

    private boolean onReportCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用此命令");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§c用法: /report <玩家ID> <原因>");
            showValidReasons(sender);
            return false;
        }

        String reason = args[1];
        if (!VALID_REASONS.contains(reason)) {
            sender.sendMessage("§c无效举报原因");
            showValidReasons(sender);
            return false;
        }

        Player reporter = (Player) sender;
        String target = args[0];
        Player targetPlayer = Bukkit.getPlayer(target);

        if (targetPlayer == null) {
            sender.sendMessage("§c目标玩家不在线");
            return true;
        }

        // 检查冷却时间
        long currentTime = System.currentTimeMillis();
        long lastReport = lastReportTime.getOrDefault(reporter.getUniqueId(), 0L);

        if (currentTime - lastReport < 30000) { // 30秒冷却
            sender.sendMessage("§c举报冷却中，请稍后再试");
            return true;
        }

        lastReportTime.put(reporter.getUniqueId(), currentTime);

        // 向举报者发送确认消息
        sender.sendMessage("§a您对§6" + target + "§a的举报已提交");

        // 根据VIP状态设置不同的处理延迟
        long delay = vipPlayers.contains(reporter.getUniqueId()) ? VIP_PROCESS_DELAY : NORMAL_PROCESS_DELAY;

        // 安排延迟处理
        new BukkitRunnable() {
            @Override
            public void run() {
                if (targetPlayer.isOnline()) {
                    long duration = BAN_DURATIONS.get(reason);
                    executeBan(targetPlayer.getName(), reason, duration);

                    // 向全服广播封禁消息
                    Bukkit.broadcastMessage("§e有一名§c" + reason + "§e的§6" + targetPlayer.getName() + "§e被处理了！");
                } else {
                    // 目标玩家已离线，取消处理
                    reporter.sendMessage("§c目标玩家已离线，举报处理已取消");
                }
            }
        }.runTaskLater(this, delay);

        return true;
    }

    private boolean onVipCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§c只有管理员可以管理VIP");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§c用法: /vip <add/remove> <玩家ID> [等级]");
            return false;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§c玩家不在线");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add":
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /vip add <玩家ID> <等级>");
                    return false;
                }
                try {
                    int level = Integer.parseInt(args[2]);
                    vipPlayers.add(target.getUniqueId());
                    vipLevels.put(target.getUniqueId(), level);
                    sender.sendMessage("§a已添加VIP: " + target.getName() + " 等级: " + level);
                    updatePlayerPrefix(target);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c等级必须是一个数字");
                }
                break;
            case "remove":
                vipPlayers.remove(target.getUniqueId());
                vipLevels.remove(target.getUniqueId());
                sender.sendMessage("§a已移除VIP: " + target.getName());
                updatePlayerPrefix(target);
                break;
            default:
                sender.sendMessage("§c无效操作");
        }
        return true;
    }

    private void executeBan(String target, String reason, long durationMs) {
        Date expiry = new Date(System.currentTimeMillis() + durationMs);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String expiryStr = dateFormat.format(expiry);

        // 从配置文件读取 QQ 号码和邮箱信息
        String qq = getConfig().getString("contact.qq", "123456");
        String email = getConfig().getString("contact.email", "123@456.com");
        String contactMessage = "如果你觉得这是误封请你联系QQ：" + qq + "；或邮箱" + email;

        String banReason = "[" + reason + "] 解封时间: " + expiryStr + "\n" + contactMessage;

        Bukkit.getBanList(BanList.Type.NAME).addBan(
                target,
                banReason,
                expiry,
                null
        );
        Player player = Bukkit.getPlayer(target);
        if (player != null) {
            player.kickPlayer(banReason);
        }
    }

    private void showValidReasons(CommandSender sender) {
        sender.sendMessage("§6有效原因: " + String.join(", ", VALID_REASONS));
    }

    private void loadVipData() {
        // 从配置文件加载VIP数据
        getConfig().options().copyDefaults(true);
        saveConfig();

        // 加载VIP玩家
        List<String> vipList = getConfig().getStringList("vip.players");
        for (String entry : vipList) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                try {
                    UUID uuid = UUID.fromString(parts[0]);
                    int level = Integer.parseInt(parts[1]);
                    vipPlayers.add(uuid);
                    vipLevels.put(uuid, level);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("无法解析VIP数据: " + entry);
                }
            }
        }
    }

    private void saveVipData() {
        // 保存VIP数据到配置文件
        List<String> vipList = new ArrayList<>();
        for (UUID uuid : vipPlayers) {
            int level = vipLevels.getOrDefault(uuid, 1);
            vipList.add(uuid.toString() + ":" + level);
        }

        getConfig().set("vip.players", vipList);
        saveConfig();
    }

    // 设置信任管理器以处理SSL证书问题
    private void setupTrustManager() {
        try {
            // 创建一个信任所有证书的信任管理器
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            // 安装信任管理器
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // 创建一个允许所有主机名的主机名验证器
            javax.net.ssl.HostnameVerifier allHostsValid = new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            };

            // 安装主机名验证器
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            getLogger().warning("设置信任管理器时发生错误: " + e.getMessage());
        }
    }

    // 启动检查更新任务
    private void startUpdateCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkForUpdates();
            }
        }.runTaskTimerAsynchronously(this, 0L, UPDATE_CHECK_INTERVAL);
    }

    // 检查更新
    private void checkForUpdates() {
        try {
            URL url = new URL(GITHUB_API_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");

            if (connection.getResponseCode() == 200) {
                Scanner scanner = new Scanner(connection.getInputStream());
                StringBuilder response = new StringBuilder();
                while (scanner.hasNextLine()) {
                    response.append(scanner.nextLine());
                }
                scanner.close();

                JSONObject json = new JSONObject(response.toString());
                String latestVersion = json.getString("tag_name");

                // 从plugin.yml获取实际版本号
                String currentVersion = getDescription().getVersion();

                // 提取版本号中的数字部分（处理类似"Vip&Report-2.19"的格式）
                String cleanCurrentVersion = extractVersionNumber(currentVersion);
                String cleanLatestVersion = extractVersionNumber(latestVersion);

                getLogger().info("当前版本: " + currentVersion + " → 提取为: " + cleanCurrentVersion);
                getLogger().info("最新版本: " + latestVersion + " → 提取为: " + cleanLatestVersion);

                if (!cleanCurrentVersion.equals(cleanLatestVersion) && isVersionOlder(cleanCurrentVersion, cleanLatestVersion)) {
                    notifyAdminsAboutUpdate(cleanCurrentVersion, cleanLatestVersion);
                } else {
                    getLogger().info("插件已是最新版本: " + cleanCurrentVersion);
                }
            }
        } catch (IOException e) {
            getLogger().warning("检查更新时发生错误: " + e.getMessage());
        }
    }

    // 从字符串中提取版本号（例如从"Vip&Report-2.19"提取"2.19"）
    private String extractVersionNumber(String input) {
        // 使用正则表达式匹配数字和点号组成的版本号
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\d+(\\.\\d+)*");
        java.util.regex.Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return matcher.group(0);
        }

        // 如果没有匹配到，返回原始字符串（但这种情况应该很少发生）
        getLogger().severe("无法从 '" + input + "' 中提取版本号！请检查GitHub Release标签格式是否正确。");
        return input;
    }

    // 比较两个版本号
    private boolean isVersionOlder(String current, String latest) {
        try {
            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

            int maxLength = Math.max(currentParts.length, latestParts.length);

            for (int i = 0; i < maxLength; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;

                getLogger().info("比较版本部分: 当前[" + i + "]=" + currentPart + ", 最新[" + i + "]=" + latestPart);

                if (currentPart < latestPart) {
                    return true;
                } else if (currentPart > latestPart) {
                    return false;
                }
                // 相等则继续比较下一部分
            }

            // 所有部分都相等
            return false;
        } catch (NumberFormatException e) {
            getLogger().severe("版本号格式错误: " + e.getMessage());
            return false;
        }
    }

    // 通知管理员更新（同时在终端显示）
    private void notifyAdminsAboutUpdate(String currentVersion, String latestVersion) {
        String message = "插件[VIP&Report]版本过时！当前版本: " + currentVersion + "，最新版本: " + latestVersion
                + "。请前往‘https://github.com/billbilW/Mc1.20.1-VIPandReport/releases’获取更新。";

        // 1. 向终端（控制台）输出提示
        getLogger().warning(message);

        // 2. 向在线管理员（OP）的游戏内聊天窗口发送提示
        String inGameMessage = "§c" + message;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(inGameMessage);
            }
        }
    }
}