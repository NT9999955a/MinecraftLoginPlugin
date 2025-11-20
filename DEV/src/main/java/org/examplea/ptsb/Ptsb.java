package org.examplea.ptsb;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class Ptsb extends JavaPlugin implements Listener {

    private FileConfiguration playerData;
    private File playerDataFile;
    private final Map<UUID, Boolean> loggedInPlayers = new HashMap<>();
    private final Map<UUID, Integer> loginAttempts = new HashMap<>();

    // 配置选项
    private boolean requireLogin;
    private boolean allowMovement;
    private int maxLoginAttempts;
    private int sessionTimeout;

    @Override
    public void onEnable() {
        // 创建配置文件和玩家数据文件
        setupConfig();
        setupPlayerDataFile();

        // 加载配置
        loadConfig();

        // 注册事件
        getServer().getPluginManager().registerEvents(this, this);

        // 注册命令
        getCommand("login").setExecutor(new LoginCommand());
        getCommand("register").setExecutor(new RegisterCommand());
        getCommand("changepassword").setExecutor(new ChangePasswordCommand());
        getCommand("loginreload").setExecutor(new ReloadCommand());

        // 启动会话检查任务
        startSessionCheckTask();

        getLogger().info("登录插件已启用");
    }

    @Override
    public void onDisable() {
        savePlayerData();
        getLogger().info("登录插件已禁用");
    }

    private void setupConfig() {
        // 检查配置文件是否存在，如果不存在则创建
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            // 创建数据文件夹
            if (!getDataFolder().mkdirs()) {
                getLogger().warning("无法创建插件数据文件夹");
            }

            // 创建默认配置
            FileConfiguration config = getConfig();
            config.set("require-login", true);
            config.set("allow-movement-before-login", false);
            config.set("max-login-attempts", 3);
            config.set("session-timeout-minutes", 30);

            saveConfig();
            getLogger().info("已创建默认配置文件");
        }
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        requireLogin = config.getBoolean("require-login", true);
        allowMovement = config.getBoolean("allow-movement-before-login", false);
        maxLoginAttempts = config.getInt("max-login-attempts", 3);
        sessionTimeout = config.getInt("session-timeout-minutes", 30);
    }

    private void setupPlayerDataFile() {
        playerDataFile = new File(getDataFolder(), "players.yml");
        if (!playerDataFile.exists()) {
            try {
                if (!playerDataFile.getParentFile().mkdirs()) {
                    getLogger().warning("无法创建玩家数据文件夹目录");
                }
                if (!playerDataFile.createNewFile()) {
                    getLogger().warning("无法创建玩家数据文件");
                } else {
                    getLogger().info("已创建玩家数据文件");
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "无法创建玩家数据文件: " + e.getMessage(), e);
            }
        }
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "无法保存玩家数据文件: " + e.getMessage(), e);
        }
    }

    private void startSessionCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                for (UUID uuid : loggedInPlayers.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline() && isLoggedIn(player)) {
                        long lastActivity = playerData.getLong(uuid + ".last-activity", 0);
                        if (currentTime - lastActivity > (long) sessionTimeout * 60L * 1000L) {
                            loggedInPlayers.put(uuid, false);
                            player.sendMessage(ChatColor.YELLOW + "会话超时，请重新登录！");
                            player.sendMessage(ChatColor.GREEN + "请使用 /login <密码> 登录");
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L * 60L, 20L * 60L);
    }

    // 密码加密
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            getLogger().log(Level.SEVERE, "密码加密算法不可用: " + e.getMessage(), e);
            return password;
        }
    }

    // 检查玩家是否已登录
    private boolean isLoggedIn(Player player) {
        return loggedInPlayers.getOrDefault(player.getUniqueId(), false);
    }

    // 玩家登录
    private void loginPlayer(Player player, String password) {
        UUID uuid = player.getUniqueId();
        String storedHash = playerData.getString(uuid + ".password");

        if (storedHash == null) {
            player.sendMessage(ChatColor.RED + "您尚未注册！请使用 /register <密码> <确认密码> 注册");
            return;
        }

        if (storedHash.equals(hashPassword(password))) {
            loggedInPlayers.put(uuid, true);
            playerData.set(uuid + ".last-activity", System.currentTimeMillis());
            savePlayerData();

            loginAttempts.remove(uuid);
            player.sendMessage(ChatColor.GREEN + "登录成功！");
        } else {
            int attempts = loginAttempts.getOrDefault(uuid, 0) + 1;
            loginAttempts.put(uuid, attempts);

            if (attempts >= maxLoginAttempts) {
                player.kickPlayer(ChatColor.RED + "登录尝试次数过多！");
            } else {
                player.sendMessage(ChatColor.RED + "密码错误！剩余尝试次数: " + (maxLoginAttempts - attempts));
            }
        }
    }

    // 玩家注册
    private void registerPlayer(Player player, String password) {
        UUID uuid = player.getUniqueId();

        if (playerData.contains(uuid.toString())) {
            player.sendMessage(ChatColor.RED + "您已经注册过了！请使用 /login <密码> 登录");
            return;
        }

        String hashedPassword = hashPassword(password);
        playerData.set(uuid + ".password", hashedPassword);
        playerData.set(uuid + ".register-time", System.currentTimeMillis());
        playerData.set(uuid + ".last-activity", System.currentTimeMillis());
        savePlayerData();

        loggedInPlayers.put(uuid, true);
        player.sendMessage(ChatColor.GREEN + "注册成功！已自动登录");
    }

    // 更改密码
    private void changePassword(Player player, String oldPassword, String newPassword) {
        UUID uuid = player.getUniqueId();
        String storedHash = playerData.getString(uuid + ".password");

        if (storedHash == null) {
            player.sendMessage(ChatColor.RED + "您尚未注册！");
            return;
        }

        if (!storedHash.equals(hashPassword(oldPassword))) {
            player.sendMessage(ChatColor.RED + "原密码错误！");
            return;
        }

        String newHashedPassword = hashPassword(newPassword);
        playerData.set(uuid + ".password", newHashedPassword);
        savePlayerData();

        player.sendMessage(ChatColor.GREEN + "密码修改成功！");
    }

    // 事件处理
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!requireLogin) {
            loggedInPlayers.put(uuid, true);
            return;
        }

        if (playerData.contains(uuid.toString())) {
            loggedInPlayers.put(uuid, false);
            player.sendMessage(ChatColor.YELLOW + "========== 登录系统 ==========");
            player.sendMessage(ChatColor.GREEN + "请使用 /login <密码> 登录");
            player.sendMessage(ChatColor.GREEN + "忘记密码请联系管理员");
            player.sendMessage(ChatColor.YELLOW + "=============================");

            // 移除了强制传送代码，玩家将保持在他们登录时的位置
        } else {
            loggedInPlayers.put(uuid, false);
            player.sendMessage(ChatColor.YELLOW + "========== 注册系统 ==========");
            player.sendMessage(ChatColor.GREEN + "欢迎新玩家！请使用 /register <密码> <确认密码> 注册");
            player.sendMessage(ChatColor.AQUA + "请妥善保管您的密码！");
            player.sendMessage(ChatColor.YELLOW + "=============================");

            // 移除了强制传送代码，新玩家将保持在他们加入服务器时的位置
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (isLoggedIn(player)) {
            playerData.set(uuid + ".last-activity", System.currentTimeMillis());
            savePlayerData();
        }

        loggedInPlayers.remove(uuid);
        loginAttempts.remove(uuid);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        if (!isLoggedIn(player) && !allowMovement) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                event.setTo(from);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        if (!isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请先登录后再发言！");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        if (!isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请先登录后再进行此操作！");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        if (!isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请先登录后再进行此操作！");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        if (!isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!requireLogin) return;

        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            if (!isLoggedIn(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!requireLogin) return;

        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (!isLoggedIn(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "请先登录后再进行此操作！");
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!requireLogin) return;

        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!isLoggedIn(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!requireLogin) return;

        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (!isLoggedIn(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "请先登录后再进行此操作！");
            }
        }

        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!isLoggedIn(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!requireLogin) return;

        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase();

        if (message.startsWith("/login") || message.startsWith("/register") ||
                message.startsWith("/changepassword") || message.startsWith("/loginreload")) {
            return;
        }

        if (!isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请先登录后再使用命令！");
        }
    }

    // 命令执行器类
    private class LoginCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家才能使用此命令！");
                return true;
            }

            Player player = (Player) sender;

            if (isLoggedIn(player)) {
                player.sendMessage(ChatColor.GREEN + "您已经登录了！");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "用法: /login <密码>");
                return true;
            }

            loginPlayer(player, args[0]);
            return true;
        }
    }

    private class RegisterCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家才能使用此命令！");
                return true;
            }

            Player player = (Player) sender;

            if (isLoggedIn(player)) {
                player.sendMessage(ChatColor.GREEN + "您已经登录了！");
                return true;
            }

            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "用法: /register <密码> <确认密码>");
                return true;
            }

            if (!args[0].equals(args[1])) {
                player.sendMessage(ChatColor.RED + "两次输入的密码不一致！");
                return true;
            }

            if (args[0].length() < 4) {
                player.sendMessage(ChatColor.RED + "密码长度至少为4位！");
                return true;
            }

            registerPlayer(player, args[0]);
            return true;
        }
    }

    private class ChangePasswordCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家才能使用此命令！");
                return true;
            }

            Player player = (Player) sender;

            if (!isLoggedIn(player)) {
                player.sendMessage(ChatColor.RED + "请先登录后再修改密码！");
                return true;
            }

            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "用法: /changepassword <原密码> <新密码>");
                return true;
            }

            if (args[1].length() < 4) {
                player.sendMessage(ChatColor.RED + "新密码长度至少为4位！");
                return true;
            }

            changePassword(player, args[0], args[1]);
            return true;
        }
    }

    private class ReloadCommand implements org.bukkit.command.CommandExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!sender.hasPermission("loginplugin.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令！");
                return true;
            }

            loadConfig();
            sender.sendMessage(ChatColor.GREEN + "登录插件配置已重载！");
            return true;
        }
    }
}