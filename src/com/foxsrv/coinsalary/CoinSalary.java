package com.foxsrv.coinsalary;

import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;
import com.foxsrv.coincard.CoinCardPlugin.TransferCallback;
import com.google.gson.*;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class CoinSalary extends JavaPlugin implements Listener {

    // ====================================================
    // CONSTANTS & CONFIG
    // ====================================================
    private static final DecimalFormat COIN_FORMAT;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("0.########", symbols);
    }

    private FileConfiguration config;
    private File lastSalaryFile;
    private LastSalaryData lastSalaryData;
    
    // Config values
    private String serverCardId;
    private long cooldownMs;
    private long salaryIntervalSeconds;
    private boolean payOffline;
    private Map<String, BigDecimal> groupSalaries = new HashMap<>();
    
    // CoinCard API
    private CoinCardAPI coinCardAPI;
    
    // Vault Permission
    private Permission permission;
    
    // Salary tracking
    private final Map<UUID, Long> lastSalaryTime = new ConcurrentHashMap<>();
    private BukkitTask salaryTask;
    
    // Cache para evitar chamadas repetidas a API
    private final Map<UUID, String> cardCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cardCacheTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 5 * 60 * 1000; // 5 minutos
    
    // Cache para grupos de jogadores offline
    private final Map<UUID, List<String>> playerGroupsCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerGroupsCacheTimestamp = new ConcurrentHashMap<>();
    private static final long GROUPS_CACHE_DURATION = 10 * 60 * 1000; // 10 minutos
    
    // ====================================================
    // PAYMENT QUEUE SYSTEM
    // ====================================================
    private final BlockingQueue<PaymentTask> paymentQueue = new LinkedBlockingQueue<>();
    private boolean isProcessingQueue = false;
    private final Object queueLock = new Object();
    private ScheduledExecutorService queueExecutor;
    private CompletableFuture<Void> queueProcessingFuture;

    /**
     * Classe interna para representar uma tarefa de pagamento
     */
    private static class PaymentTask {
        final OfflinePlayer player;
        final BigDecimal amount;
        final String playerCardId;
        final String playerName;
        final boolean isOnline;
        final UUID uuid;
        
        PaymentTask(OfflinePlayer player, BigDecimal amount, String playerCardId) {
            this.player = player;
            this.amount = amount;
            this.playerCardId = playerCardId;
            this.playerName = player.getName() != null ? player.getName() : "Unknown";
            this.isOnline = player.isOnline();
            this.uuid = player.getUniqueId();
        }
    }

    // ====================================================
    // ON ENABLE / DISABLE
    // ====================================================
    @Override
    public void onEnable() {
        // Check if CoinCard is installed
        if (!setupCoinCardAPI()) {
            getLogger().severe("CoinCard plugin not found! Disabling CoinSalary...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Setup Vault Permission
        if (!setupVaultPermission()) {
            getLogger().severe("Vault permission system not found! Disabling CoinSalary...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveResource("config.yml", false);
        }
        
        setupFolders();
        loadConfig();
        loadLastSalaryData();
        
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("salary")).setExecutor(new SalaryCommand());
        Objects.requireNonNull(getCommand("salaries")).setExecutor(new SalariesCommand());

        COIN_FORMAT.setRoundingMode(RoundingMode.DOWN);
        COIN_FORMAT.setMinimumFractionDigits(0);
        COIN_FORMAT.setMaximumFractionDigits(8);

        // Initialize queue executor
        queueExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CoinSalary-Queue-Processor");
            t.setDaemon(true);
            return t;
        });

        startSalaryTask();

        getLogger().info("CoinSalary v" + getDescription().getVersion() + " enabled successfully with CoinCard integration!");
        getLogger().info("Salary interval: " + salaryIntervalSeconds + " seconds");
        getLogger().info("Pay offline players: " + payOffline);
        getLogger().info("Transaction cooldown: " + cooldownMs + "ms");
        getLogger().info("Loaded " + groupSalaries.size() + " salary groups");
    }

    @Override
    public void onDisable() {
        if (salaryTask != null) {
            salaryTask.cancel();
        }
        
        // Shutdown queue executor gracefully
        if (queueExecutor != null) {
            queueExecutor.shutdown();
            try {
                if (!queueExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    queueExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                queueExecutor.shutdownNow();
            }
        }
        
        saveLastSalaryData();
        cardCache.clear();
        cardCacheTimestamp.clear();
        playerGroupsCache.clear();
        playerGroupsCacheTimestamp.clear();
        getLogger().info("CoinSalary disabled.");
    }

    private void setupFolders() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        lastSalaryFile = new File(getDataFolder(), "last_salary.dat");
    }

    // ====================================================
    // LAST SALARY DATA STORAGE
    // ====================================================
    private void loadLastSalaryData() {
        if (lastSalaryFile.exists()) {
            try (Reader reader = new FileReader(lastSalaryFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                lastSalaryData = new LastSalaryData();
                
                if (jsonObject.has("lastPayments")) {
                    JsonObject paymentsObject = jsonObject.getAsJsonObject("lastPayments");
                    for (Map.Entry<String, JsonElement> entry : paymentsObject.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            long timestamp = entry.getValue().getAsLong();
                            lastSalaryData.lastPayments.put(uuid, timestamp);
                            lastSalaryTime.put(uuid, timestamp);
                        } catch (Exception e) {
                            getLogger().warning("Failed to load last payment for " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                }
                
                if (jsonObject.has("lastTaskRun")) {
                    lastSalaryData.lastTaskRun = jsonObject.get("lastTaskRun").getAsLong();
                }
                
                getLogger().info("Loaded " + lastSalaryData.lastPayments.size() + " last payment records.");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.WARNING, "Failed to load last salary data, creating new", e);
                lastSalaryData = new LastSalaryData();
            }
        } else {
            lastSalaryData = new LastSalaryData();
            getLogger().info("Created new last salary data file.");
        }
    }

    private void saveLastSalaryData() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            
            JsonObject jsonObject = new JsonObject();
            JsonObject paymentsObject = new JsonObject();
            
            for (Map.Entry<UUID, Long> entry : lastSalaryTime.entrySet()) {
                paymentsObject.addProperty(entry.getKey().toString(), entry.getValue());
            }
            
            jsonObject.add("lastPayments", paymentsObject);
            jsonObject.addProperty("lastTaskRun", lastSalaryData.lastTaskRun);
            
            try (Writer writer = new FileWriter(lastSalaryFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to save last salary data", e);
        }
    }

    // ====================================================
    // COINCARD API SETUP
    // ====================================================
    private boolean setupCoinCardAPI() {
        try {
            RegisteredServiceProvider<CoinCardAPI> provider = 
                getServer().getServicesManager().getRegistration(CoinCardAPI.class);

            if (provider == null) {
                return false;
            }

            coinCardAPI = provider.getProvider();
            return coinCardAPI != null;
        } catch (Exception e) {
            getLogger().severe("Failed to setup CoinCard API: " + e.getMessage());
            return false;
        }
    }

    // ====================================================
    // VAULT PERMISSION SETUP
    // ====================================================
    private boolean setupVaultPermission() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = 
            getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }
        permission = rsp.getProvider();
        return permission != null;
    }

    // ====================================================
    // CONFIGURATION
    // ====================================================
    private void loadConfig() {
        reloadConfig();
        config = getConfig();

        config.addDefault("Server", "e1301fadfc35");
        config.addDefault("Cooldown", 1100);
        config.addDefault("Interval", 3600);
        config.addDefault("offline", false);
        
        // Default salary groups
        config.addDefault("Groups.default", 0.00000000);
        config.addDefault("Groups.vip", 0.00000055);
        config.addDefault("Groups.admin", 0.00100000);
        config.addDefault("Groups.builder", 0.00050000);
        
        config.options().copyDefaults(true);
        saveConfig();

        serverCardId = config.getString("Server", "e1301fadfc35");
        cooldownMs = config.getLong("Cooldown", 1100);
        salaryIntervalSeconds = config.getLong("Interval", 3600);
        payOffline = config.getBoolean("offline", false);
        
        // Load salary groups
        groupSalaries.clear();
        if (config.isConfigurationSection("Groups")) {
            for (String group : config.getConfigurationSection("Groups").getKeys(false)) {
                double salary = config.getDouble("Groups." + group, 0.0);
                groupSalaries.put(group.toLowerCase(), BigDecimal.valueOf(salary));
                getLogger().info("Loaded salary group: " + group + " = " + formatCoin(BigDecimal.valueOf(salary)));
            }
        }
    }

    /**
     * Salva configuracao manualmente (para comando /salary group)
     */
    private void saveGroupConfig(String group, BigDecimal amount) {
        groupSalaries.put(group.toLowerCase(), amount);
        config.set("Groups." + group, amount.doubleValue());
        saveConfig();
    }

    /**
     * Remove grupo da configuracao
     */
    private void removeGroupConfig(String group) {
        groupSalaries.remove(group.toLowerCase());
        config.set("Groups." + group, null);
        saveConfig();
    }

    // ====================================================
    // COINCARD API HELPERS (ASSINCRONOS)
    // ====================================================
    
    /**
     * Obtem o card ID de um jogador usando a API do CoinCard (assincrono)
     */
    private CompletableFuture<String> getPlayerCardIdAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (uuid == null) return null;
            
            // Verificar cache primeiro
            Long cachedTime = cardCacheTimestamp.get(uuid);
            if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CACHE_DURATION) {
                String cached = cardCache.get(uuid);
                if (cached != null) return cached;
            }
            
            // Chamar a API para obter o card
            String cardId = coinCardAPI.getPlayerCard(uuid);
            
            // Atualizar cache
            if (cardId != null && !cardId.isEmpty()) {
                cardCache.put(uuid, cardId);
                cardCacheTimestamp.put(uuid, System.currentTimeMillis());
            }
            
            return cardId;
        });
    }
    
    /**
     * Verifica se um jogador tem card configurado (assincrono)
     */
    private CompletableFuture<Boolean> hasPlayerCardAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (uuid == null) return false;
            
            // Verificar cache primeiro
            if (cardCache.containsKey(uuid)) {
                String cached = cardCache.get(uuid);
                return cached != null && !cached.isEmpty();
            }
            
            // Chamar a API
            boolean hasCard = coinCardAPI.hasCard(uuid);
            
            // Se tiver card, atualizar cache
            if (hasCard) {
                String cardId = coinCardAPI.getPlayerCard(uuid);
                if (cardId != null && !cardId.isEmpty()) {
                    cardCache.put(uuid, cardId);
                    cardCacheTimestamp.put(uuid, System.currentTimeMillis());
                }
            }
            
            return hasCard;
        });
    }
    
    // ====================================================
    // VAULT HELPERS (ASSINCRONOS - SEM LAG)
    // ====================================================
    
    /**
     * Obtem os grupos de um jogador usando Vault de forma assincrona
     */
    private CompletableFuture<List<String>> getPlayerGroupsAsync(OfflinePlayer player) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> groups = new ArrayList<>();
            
            if (player == null) return groups;
            
            UUID uuid = player.getUniqueId();
            
            // Verificar cache para jogadores offline
            if (!player.isOnline()) {
                Long cachedTime = playerGroupsCacheTimestamp.get(uuid);
                if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < GROUPS_CACHE_DURATION) {
                    List<String> cached = playerGroupsCache.get(uuid);
                    if (cached != null) return cached;
                }
            }
            
            try {
                if (player.isOnline()) {
                    // Jogador online - pegar grupos diretamente
                    Player onlinePlayer = player.getPlayer();
                    if (onlinePlayer != null) {
                        CompletableFuture<List<String>> future = new CompletableFuture<>();
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                String[] playerGroups = permission.getPlayerGroups(onlinePlayer);
                                List<String> result = new ArrayList<>();
                                if (playerGroups != null) {
                                    result.addAll(Arrays.asList(playerGroups));
                                }
                                future.complete(result);
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                        });
                        
                        try {
                            groups = future.get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            getLogger().warning("Failed to get groups for online player " + player.getName() + ": " + e.getMessage());
                        }
                    }
                } else {
                    // Jogador offline
                    CompletableFuture<List<String>> future = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            String[] playerGroups = permission.getPlayerGroups(null, player);
                            List<String> result = new ArrayList<>();
                            if (playerGroups != null) {
                                result.addAll(Arrays.asList(playerGroups));
                            }
                            future.complete(result);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });
                    
                    try {
                        groups = future.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        getLogger().warning("Failed to get groups for offline player " + player.getName() + ": " + e.getMessage());
                    }
                }
                
                // Sempre adicionar grupo default se existir e o jogador nao tiver grupos
                if (groups.isEmpty() && groupSalaries.containsKey("default")) {
                    groups.add("default");
                }
                
                // Atualizar cache para jogadores offline
                if (!player.isOnline()) {
                    playerGroupsCache.put(uuid, new ArrayList<>(groups));
                    playerGroupsCacheTimestamp.put(uuid, System.currentTimeMillis());
                }
                
            } catch (Exception e) {
                getLogger().warning("Failed to get groups for " + player.getName() + ": " + e.getMessage());
            }
            
            return groups;
        });
    }
    
    /**
     * Calcula o salario total baseado nos grupos do jogador (VIA VAULT) - ASSINCRONO
     */
    private CompletableFuture<BigDecimal> calculateSalaryAsync(OfflinePlayer player) {
        if (player == null) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        
        return getPlayerGroupsAsync(player).thenApply(groups -> {
            BigDecimal total = BigDecimal.ZERO;
            
            // Somar salarios de todos os grupos que o jogador participa
            for (String group : groups) {
                BigDecimal salary = groupSalaries.get(group.toLowerCase());
                if (salary != null) {
                    total = total.add(salary);
                }
            }
            
            return total;
        });
    }
    
    /**
     * Versao sincrona para comandos (usa cache)
     */
    private BigDecimal calculateSalarySync(OfflinePlayer player) {
        if (player == null) return BigDecimal.ZERO;
        
        try {
            return calculateSalaryAsync(player).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().warning("Failed to calculate salary for " + player.getName() + " synchronously: " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Versao sincrona para grupos (usa cache)
     */
    private List<String> getPlayerGroupsSync(OfflinePlayer player) {
        if (player == null) return new ArrayList<>();
        
        try {
            return getPlayerGroupsAsync(player).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().warning("Failed to get groups for " + player.getName() + " synchronously: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    // ====================================================
    // PAYMENT QUEUE PROCESSING
    // ====================================================
    
    /**
     * Adiciona um pagamento a fila para processamento assincrono
     */
    private void queuePayment(OfflinePlayer player, BigDecimal amount, String playerCardId) {
        PaymentTask task = new PaymentTask(player, amount, playerCardId);
        paymentQueue.offer(task);
        getLogger().info("Added " + task.playerName + " to payment queue. Queue size: " + paymentQueue.size());
        
        // Iniciar processamento da fila se nao estiver rodando
        startQueueProcessing();
    }
    
    /**
     * Inicia o processamento da fila de pagamentos (se ja nao estiver rodando)
     */
    private synchronized void startQueueProcessing() {
        if (isProcessingQueue) {
            return;
        }
        
        isProcessingQueue = true;
        getLogger().info("Starting payment queue processor...");
        
        queueProcessingFuture = CompletableFuture.runAsync(() -> {
            while (isProcessingQueue) {
                try {
                    // Pegar proximo item da fila (bloqueante)
                    PaymentTask task = paymentQueue.poll(1, TimeUnit.SECONDS);
                    
                    if (task == null) {
                        // Fila vazia, verificar se devemos parar
                        synchronized (queueLock) {
                            if (paymentQueue.isEmpty()) {
                                isProcessingQueue = false;
                                getLogger().info("Payment queue processor stopped (queue empty)");
                                break;
                            }
                        }
                        continue;
                    }
                    
                    // Processar o pagamento
                    processSinglePayment(task);
                    
                    // Aguardar o cooldown configurado antes do proximo pagamento
                    Thread.sleep(cooldownMs);
                    
                } catch (InterruptedException e) {
                    getLogger().warning("Payment queue processor interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    getLogger().severe("Error in payment queue processor: " + e.getMessage());
                }
            }
        }, queueExecutor);
    }
    
    /**
     * Processa um unico pagamento da fila
     */
    private void processSinglePayment(PaymentTask task) {
        final double fAmount = task.amount.doubleValue();
        final String fPlayerCard = task.playerCardId;
        final String fServerCard = serverCardId;
        final String playerName = task.playerName;
        final OfflinePlayer player = task.player;
        
        getLogger().info("Processing queue payment: " + formatCoin(task.amount) + " to " + playerName);
        
        // Usar CountDownLatch para aguardar o callback
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        final String[] errorMsg = {null};
        
        // Executar transferencia na thread do CoinCard (ja estamos em thread separada)
        coinCardAPI.transfer(fServerCard, fPlayerCard, fAmount, new TransferCallback() {
            @Override
            public void onSuccess(String txId, double amount) {
                success[0] = true;
                
                // Notificar jogador se estiver online (voltar para main thread)
                if (player.isOnline()) {
                    Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                        Player onlinePlayer = player.getPlayer();
                        if (onlinePlayer != null) {
                            onlinePlayer.sendMessage(ChatColor.GREEN + "You received salary: " + 
                                    ChatColor.YELLOW + formatCoin(BigDecimal.valueOf(amount)) + 
                                    ChatColor.GREEN + " coins! Transaction: " + 
                                    ChatColor.AQUA + (txId != null ? txId : "-"));
                        }
                    });
                }
                
                getLogger().info("Queue payment successful: " + formatCoin(BigDecimal.valueOf(amount)) + 
                        " to " + playerName + " tx=" + txId);
                
                latch.countDown();
            }

            @Override
            public void onFailure(String error) {
                success[0] = false;
                errorMsg[0] = error;
                
                // Notificar jogador se estiver online (voltar para main thread)
                if (player.isOnline()) {
                    Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                        Player onlinePlayer = player.getPlayer();
                        if (onlinePlayer != null) {
                            onlinePlayer.sendMessage(ChatColor.RED + "Failed to receive salary: " + error);
                        }
                    });
                }
                
                getLogger().warning("Queue payment failed for " + playerName + ": " + error);
                
                latch.countDown();
            }
        });
        
        // Aguardar callback (com timeout)
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                getLogger().warning("Payment timeout for " + playerName);
            }
        } catch (InterruptedException e) {
            getLogger().warning("Payment interrupted for " + playerName);
            Thread.currentThread().interrupt();
        }
    }

    // ====================================================
    // SALARY TASK
    // ====================================================
    private void startSalaryTask() {
        long intervalTicks = salaryIntervalSeconds * 20; // Converter segundos para ticks
        
        if (salaryTask != null) {
            salaryTask.cancel();
        }
        
        salaryTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Executar processamento de forma assincrona
                Bukkit.getScheduler().runTaskAsynchronously(CoinSalary.this, () -> {
                    processSalariesAsync();
                    lastSalaryData.lastTaskRun = System.currentTimeMillis();
                    saveLastSalaryData();
                });
            }
        }.runTaskTimer(this, intervalTicks, intervalTicks);
        
        getLogger().info("Salary task started with interval " + salaryIntervalSeconds + " seconds (" + intervalTicks + " ticks)");
    }
    
    /**
     * Forca execucao da task de salario (comando /salary next) - PAGA TODOS SEM VERIFICAR COOLDOWN
     */
    private void forceRunSalaryTask() {
        getLogger().info("Forcing salary task execution (ignoring cooldowns)...");
        
        // Executar agora de forma assincrona, pagando todos sem verificar cooldown
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            forceProcessAllSalariesAsync();
            lastSalaryData.lastTaskRun = System.currentTimeMillis();
            saveLastSalaryData();
        });
        
        // Nao reiniciar a task, apenas manter a atual
    }
    
    /**
     * Processa salarios respeitando cooldown (task normal)
     */
    private void processSalariesAsync() {
        getLogger().info("Processing salaries...");
        
        if (payOffline) {
            // Pagar todos os jogadores que ja jogaram no servidor
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.hasPlayedBefore()) {
                    processPlayerSalaryAsync(offlinePlayer, true); // Verificar cooldown
                }
            }
        } else {
            // Pagar apenas jogadores online
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                processPlayerSalaryAsync(onlinePlayer, true); // Verificar cooldown
            }
        }
    }
    
    /**
     * Processa TODOS os salarios sem verificar cooldown (forcado)
     */
    private void forceProcessAllSalariesAsync() {
        getLogger().info("Force processing ALL salaries (ignoring cooldowns)...");
        
        long now = System.currentTimeMillis();
        
        if (payOffline) {
            // Pagar todos os jogadores que ja jogaram no servidor
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.hasPlayedBefore()) {
                    // Atualizar timestamp antes de pagar (forcado)
                    lastSalaryTime.put(offlinePlayer.getUniqueId(), now);
                    
                    // Processar pagamento
                    processPlayerSalaryAsync(offlinePlayer, false); // Nao verificar cooldown
                }
            }
        } else {
            // Pagar apenas jogadores online
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                // Atualizar timestamp antes de pagar (forcado)
                lastSalaryTime.put(onlinePlayer.getUniqueId(), now);
                
                // Processar pagamento
                processPlayerSalaryAsync(onlinePlayer, false); // Nao verificar cooldown
            }
        }
        
        // Salvar dados apos forcar todos os pagamentos
        saveLastSalaryData();
        
        getLogger().info("Force salary processing completed! Queue size: " + paymentQueue.size());
    }
    
    /**
     * Processa salario de um jogador especifico
     * @param player O jogador
     * @param checkCooldown Se deve verificar cooldown ou nao
     */
    private void processPlayerSalaryAsync(OfflinePlayer player, boolean checkCooldown) {
        if (player == null) return;
        
        UUID uuid = player.getUniqueId();
        
        if (checkCooldown) {
            // Verificar cooldown
            Long lastPaid = lastSalaryTime.get(uuid);
            long now = System.currentTimeMillis();
            long intervalMs = salaryIntervalSeconds * 1000;
            
            if (lastPaid != null && (now - lastPaid) < intervalMs) {
                return; // Ja recebeu recentemente
            }
            
            // Atualizar timestamp
            lastSalaryTime.put(uuid, now);
        }
        
        // Calcular salario baseado nos grupos do Vault (assincrono)
        calculateSalaryAsync(player).thenAccept(salary -> {
            // Pagar se houver salario
            if (salary.compareTo(BigDecimal.ZERO) > 0) {
                // Verificar se o jogador tem card
                hasPlayerCardAsync(uuid).thenAccept(hasCard -> {
                    if (!hasCard) {
                        if (player.isOnline()) {
                            Player onlinePlayer = player.getPlayer();
                            if (onlinePlayer != null) {
                                onlinePlayer.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to receive salary.");
                            }
                        }
                        getLogger().info("Player " + player.getName() + " has no card set, skipping salary");
                        return;
                    }
                    
                    // Verificar se o servidor tem card configurado
                    if (serverCardId == null || serverCardId.isEmpty()) {
                        getLogger().warning("Server card not configured! Cannot pay salary to " + player.getName());
                        return;
                    }
                    
                    // Obter card ID do jogador (assincrono)
                    getPlayerCardIdAsync(uuid).thenAccept(playerCardId -> {
                        if (playerCardId == null || playerCardId.isEmpty()) {
                            getLogger().warning("Could not get card ID for " + player.getName());
                            return;
                        }
                        
                        // Adicionar a fila de pagamentos
                        queuePayment(player, salary, playerCardId);
                    });
                });
            }
        });
    }

    // ====================================================
    // UTILITY METHODS
    // ====================================================
    private String formatCoin(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        String formatted = COIN_FORMAT.format(amount);
        if (!formatted.contains(".")) {
            formatted += ".0";
        }
        return formatted;
    }
    
    private String formatTime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + "m " + secs + "s";
        }
        if (seconds < 86400) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + "h " + minutes + "m";
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        return days + "d " + hours + "h";
    }
    
    private OfflinePlayer findPlayer(String name) {
        // Primeiro tenta online
        Player onlinePlayer = Bukkit.getPlayerExact(name);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }
        
        // Depois procura nos offline players
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op;
            }
        }
        
        return null;
    }

    // ====================================================
    // EVENT LISTENERS
    // ====================================================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Limpar cache do jogador ao entrar
        cardCache.remove(player.getUniqueId());
        cardCacheTimestamp.remove(player.getUniqueId());
        playerGroupsCache.remove(player.getUniqueId());
        playerGroupsCacheTimestamp.remove(player.getUniqueId());
        
        // Pagar salario ao entrar se estiver na hora (assincrono)
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            processPlayerSalaryAsync(player, true); // Verificar cooldown
        }, 100L); // 5 segundos apos entrar
    }

    // ====================================================
    // SALARIES COMMAND (/salaries)
    // ====================================================
    public class SalariesCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            sender.sendMessage(ChatColor.YELLOW + "=== CoinSalary Groups ===");
            sender.sendMessage(ChatColor.GRAY + "Interval: " + ChatColor.WHITE + formatTime(salaryIntervalSeconds));
            sender.sendMessage(ChatColor.GRAY + "Cooldown: " + ChatColor.WHITE + cooldownMs + "ms");
            sender.sendMessage(ChatColor.GRAY + "Pay offline: " + (payOffline ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            sender.sendMessage("");
            
            if (groupSalaries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No salary groups configured!");
                return true;
            }
            
            sender.sendMessage(ChatColor.GRAY + "Configured groups:");
            for (Map.Entry<String, BigDecimal> entry : groupSalaries.entrySet()) {
                sender.sendMessage(ChatColor.GRAY + "  * " + ChatColor.WHITE + entry.getKey() + 
                        ChatColor.GRAY + " -> " + ChatColor.GREEN + formatCoin(entry.getValue()));
            }
            
            // Mostrar fila de pagamentos
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GRAY + "Payment queue: " + ChatColor.YELLOW + paymentQueue.size() + 
                    ChatColor.GRAY + " pending | " + (isProcessingQueue ? ChatColor.GREEN + "Processing" : ChatColor.RED + "Idle"));
            
            // Mostrar proxima execucao
            if (lastSalaryData.lastTaskRun > 0) {
                long nextRun = lastSalaryData.lastTaskRun + (salaryIntervalSeconds * 1000);
                long now = System.currentTimeMillis();
                if (nextRun > now) {
                    long secondsLeft = (nextRun - now) / 1000;
                    sender.sendMessage("");
                    sender.sendMessage(ChatColor.GRAY + "Next automatic payment in: " + 
                            ChatColor.YELLOW + formatTime(secondsLeft));
                } else {
                    sender.sendMessage("");
                    sender.sendMessage(ChatColor.GREEN + "Next payment will run soon!");
                }
            }
            
            return true;
        }
        
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return new ArrayList<>(); // Sem tab complete
        }
    }

    // ====================================================
    // SALARY COMMAND (/salary)
    // ====================================================
    public class SalaryCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handleReload(sender);
                    break;
                    
                case "next":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handleNext(sender);
                    break;
                    
                case "check":
                    handleCheckCommand(sender, args);
                    break;
                    
                case "pay":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handlePayCommand(sender, args);
                    break;
                    
                case "group":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handleGroupCommand(sender, args);
                    break;
                    
                case "test":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handleTestCommand(sender, args);
                    break;
                    
                case "queue":
                    if (!sender.hasPermission("coinsalary.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    handleQueueCommand(sender);
                    break;
                    
                default:
                    sender.sendMessage(ChatColor.RED + "Unknown command. Use /salary for help.");
                    break;
            }
            
            return true;
        }
        
        private void sendHelp(CommandSender sender) {
            sender.sendMessage(ChatColor.YELLOW + "=== CoinSalary Help ===");
            sender.sendMessage(ChatColor.GREEN + "/salary reload " + ChatColor.GRAY + "- Reload configuration");
            sender.sendMessage(ChatColor.GREEN + "/salary next " + ChatColor.GRAY + "- Force run salary task now (pays everyone, ignores cooldown)");
            sender.sendMessage(ChatColor.GREEN + "/salary check [player] " + ChatColor.GRAY + "- Check salary amount");
            sender.sendMessage(ChatColor.GREEN + "/salary queue " + ChatColor.GRAY + "- Show payment queue status");
            sender.sendMessage(ChatColor.GREEN + "/salaries " + ChatColor.GRAY + "- List all salary groups");
            
            if (sender.hasPermission("coinsalary.admin")) {
                sender.sendMessage("");
                sender.sendMessage(ChatColor.YELLOW + "Admin Commands:");
                sender.sendMessage(ChatColor.GREEN + "/salary pay <player> " + ChatColor.GRAY + "- Manually pay salary");
                sender.sendMessage(ChatColor.GREEN + "/salary group list " + ChatColor.GRAY + "- List all groups");
                sender.sendMessage(ChatColor.GREEN + "/salary group <group> [amount] " + ChatColor.GRAY + "- Set/remove group");
                sender.sendMessage(ChatColor.GREEN + "/salary test <player> " + ChatColor.GRAY + "- Test show player groups");
            }
        }
        
        private void handleReload(CommandSender sender) {
            loadConfig();
            loadLastSalaryData();
            
            // Reiniciar task com novo intervalo
            startSalaryTask();
            
            sender.sendMessage(ChatColor.GREEN + "CoinSalary configuration reloaded!");
            sender.sendMessage(ChatColor.GRAY + "Interval: " + salaryIntervalSeconds + " seconds");
            sender.sendMessage(ChatColor.GRAY + "Cooldown: " + cooldownMs + "ms");
            sender.sendMessage(ChatColor.GRAY + "Pay offline: " + payOffline);
            sender.sendMessage(ChatColor.GRAY + "Groups loaded: " + groupSalaries.size());
        }
        
        private void handleNext(CommandSender sender) {
            sender.sendMessage(ChatColor.YELLOW + "Forcing salary task to run now (pays everyone, ignores cooldown)...");
            forceRunSalaryTask();
            sender.sendMessage(ChatColor.GREEN + "Salary task executed! Payments added to queue.");
        }
        
        private void handleQueueCommand(CommandSender sender) {
            sender.sendMessage(ChatColor.YELLOW + "=== Payment Queue Status ===");
            sender.sendMessage(ChatColor.GRAY + "Queue size: " + ChatColor.YELLOW + paymentQueue.size());
            sender.sendMessage(ChatColor.GRAY + "Processing: " + (isProcessingQueue ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            sender.sendMessage(ChatColor.GRAY + "Cooldown: " + ChatColor.WHITE + cooldownMs + "ms between transactions");
            
            if (!paymentQueue.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Estimated time: " + ChatColor.YELLOW + 
                        formatTime((paymentQueue.size() * cooldownMs) / 1000));
            }
        }
        
        private void handleGroupCommand(CommandSender sender, String[] args) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /salary group list");
                sender.sendMessage(ChatColor.RED + "Usage: /salary group <group> [amount]");
                return;
            }
            
            if (args[1].equalsIgnoreCase("list")) {
                // Listar todos os grupos
                sender.sendMessage(ChatColor.YELLOW + "=== Salary Groups ===");
                if (groupSalaries.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No groups configured!");
                    return;
                }
                
                for (Map.Entry<String, BigDecimal> entry : groupSalaries.entrySet()) {
                    sender.sendMessage(ChatColor.GRAY + "  * " + ChatColor.WHITE + entry.getKey() + 
                            ChatColor.GRAY + " -> " + ChatColor.GREEN + formatCoin(entry.getValue()));
                }
                return;
            }
            
            // /salary group <group> [amount]
            String group = args[1].toLowerCase();
            
            if (args.length == 2) {
                // Mostrar valor atual
                BigDecimal current = groupSalaries.get(group);
                if (current == null) {
                    sender.sendMessage(ChatColor.RED + "Group '" + group + "' not configured!");
                    sender.sendMessage(ChatColor.YELLOW + "Use: /salary group " + group + " <amount> to set it");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Group " + group + ": " + formatCoin(current));
                }
                return;
            }
            
            if (args.length >= 3) {
                String amountStr = args[2];
                
                // Se for "0" ou "remove", remover grupo
                if (amountStr.equalsIgnoreCase("0") || amountStr.equalsIgnoreCase("remove")) {
                    if (groupSalaries.containsKey(group)) {
                        removeGroupConfig(group);
                        sender.sendMessage(ChatColor.GREEN + "Removed group: " + group);
                    } else {
                        sender.sendMessage(ChatColor.RED + "Group not configured: " + group);
                    }
                    return;
                }
                
                // Configurar novo valor
                try {
                    BigDecimal amount = new BigDecimal(amountStr.replace(',', '.'));
                    if (amount.compareTo(BigDecimal.ZERO) < 0) {
                        sender.sendMessage(ChatColor.RED + "Amount must be positive!");
                        return;
                    }
                    
                    saveGroupConfig(group, amount);
                    sender.sendMessage(ChatColor.GREEN + "Set group " + group + " = " + formatCoin(amount));
                    
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount format! Use numbers like 0.00000055");
                }
            }
        }
        
        private void handleCheckCommand(CommandSender sender, String[] args) {
            OfflinePlayer target;
            String targetName;
            
            if (args.length >= 2) {
                // Verificar salario de outro jogador
                if (!sender.hasPermission("coinsalary.admin")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to check other players!");
                    return;
                }
                
                targetName = args[1];
                target = findPlayer(targetName);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found!");
                    return;
                }
            } else if (sender instanceof Player) {
                // Verificar proprio salario
                Player player = (Player) sender;
                target = player;
                targetName = player.getName();
            } else {
                sender.sendMessage(ChatColor.RED + "Please specify a player from console!");
                return;
            }
            
            // Usar metodos sincronos com cache
            BigDecimal salary = calculateSalarySync(target);
            Long lastPaid = lastSalaryTime.get(target.getUniqueId());
            long now = System.currentTimeMillis();
            
            sender.sendMessage(ChatColor.YELLOW + "=== Salary Info for " + targetName + " ===");
            sender.sendMessage(ChatColor.GRAY + "Salary amount: " + ChatColor.GREEN + formatCoin(salary));
            
            if (lastPaid != null) {
                long secondsSince = (now - lastPaid) / 1000;
                long secondsUntil = salaryIntervalSeconds - secondsSince;
                
                sender.sendMessage(ChatColor.GRAY + "Last paid: " + ChatColor.YELLOW + 
                        formatTime(secondsSince) + " ago");
                
                if (secondsUntil > 0) {
                    sender.sendMessage(ChatColor.GRAY + "Next payment in: " + ChatColor.YELLOW + 
                            formatTime(secondsUntil));
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Ready for payment!");
                }
            } else {
                sender.sendMessage(ChatColor.GRAY + "Never received salary");
                sender.sendMessage(ChatColor.GREEN + "Ready for payment!");
            }
            
            // Mostrar grupos do jogador (usando cache)
            List<String> groups = getPlayerGroupsSync(target);
            sender.sendMessage(ChatColor.GRAY + "Groups: " + ChatColor.WHITE + 
                    (groups.isEmpty() ? "none" : String.join(", ", groups)));
            
            // Verificar se tem card (assincrono com callback)
            hasPlayerCardAsync(target.getUniqueId()).thenAccept(hasCard -> {
                Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                    sender.sendMessage(ChatColor.GRAY + "Has CoinCard: " + (hasCard ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
                });
            });
        }
        
        private void handleTestCommand(CommandSender sender, String[] args) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /salary test <player>");
                return;
            }
            
            String targetName = args[1];
            OfflinePlayer target = findPlayer(targetName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return;
            }
            
            sender.sendMessage(ChatColor.YELLOW + "=== Test Groups for " + target.getName() + " ===");
            
            // Usar metodos assincronos com callback
            getPlayerGroupsAsync(target).thenAccept(groups -> {
                Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                    sender.sendMessage(ChatColor.GRAY + "Vault groups: " + ChatColor.WHITE + 
                            (groups.isEmpty() ? "none" : String.join(", ", groups)));
                    
                    // Mostrar grupos configurados que coincidem
                    sender.sendMessage(ChatColor.GRAY + "Matching salary groups:");
                    BigDecimal total = BigDecimal.ZERO;
                    for (String group : groups) {
                        BigDecimal salary = groupSalaries.get(group.toLowerCase());
                        if (salary != null) {
                            sender.sendMessage(ChatColor.GREEN + "  + " + group + ": " + formatCoin(salary));
                            total = total.add(salary);
                        } else {
                            sender.sendMessage(ChatColor.RED + "  - " + group + ": no salary configured");
                        }
                    }
                    
                    sender.sendMessage(ChatColor.GRAY + "Total salary: " + ChatColor.GREEN + formatCoin(total));
                });
            });
        }
        
        private void handlePayCommand(CommandSender sender, String[] args) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /salary pay <player>");
                return;
            }
            
            String targetName = args[1];
            OfflinePlayer target = findPlayer(targetName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return;
            }
            
            sender.sendMessage(ChatColor.YELLOW + "Processing manual payment for " + targetName + "...");
            
            // Calcular salario assincrono
            calculateSalaryAsync(target).thenAccept(salary -> {
                if (salary.compareTo(BigDecimal.ZERO) <= 0) {
                    Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                        sender.sendMessage(ChatColor.RED + targetName + " has no salary configured!");
                    });
                    return;
                }
                
                // Verificar se o jogador tem card
                hasPlayerCardAsync(target.getUniqueId()).thenAccept(hasCard -> {
                    if (!hasCard) {
                        Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                            sender.sendMessage(ChatColor.RED + targetName + " has no CoinCard configured!");
                        });
                        return;
                    }
                    
                    // Obter card ID
                    getPlayerCardIdAsync(target.getUniqueId()).thenAccept(cardId -> {
                        if (cardId == null || cardId.isEmpty()) {
                            Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                                sender.sendMessage(ChatColor.RED + "Could not get card ID for " + targetName);
                            });
                            return;
                        }
                        
                        // Atualizar timestamp antes de pagar
                        lastSalaryTime.put(target.getUniqueId(), System.currentTimeMillis());
                        saveLastSalaryData();
                        
                        // Adicionar a fila
                        queuePayment(target, salary, cardId);
                        
                        Bukkit.getScheduler().runTask(CoinSalary.this, () -> {
                            sender.sendMessage(ChatColor.GREEN + "Manual salary payment for " + targetName + 
                                    " added to queue. Amount: " + formatCoin(salary));
                        });
                    });
                });
            });
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                completions.add("reload");
                completions.add("next");
                completions.add("check");
                completions.add("queue");
                if (sender.hasPermission("coinsalary.admin")) {
                    completions.add("pay");
                    completions.add("group");
                    completions.add("test");
                }
                return filter(completions, args[0]);
            }
            
            if (args.length == 2) {
                switch (args[0].toLowerCase()) {
                    case "check":
                    case "pay":
                    case "test":
                        // Sugerir jogadores online
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            completions.add(player.getName());
                        }
                        break;
                        
                    case "group":
                        completions.add("list");
                        completions.addAll(groupSalaries.keySet());
                        break;
                }
                return filter(completions, args[1]);
            }
            
            if (args.length == 3 && args[0].equalsIgnoreCase("group")) {
                // Sugerir valores padrao
                completions.add("0.00000000");
                completions.add("0.00000055");
                completions.add("0.00100000");
                completions.add("remove");
                return filter(completions, args[2]);
            }

            return completions;
        }
        
        private List<String> filter(List<String> base, String token) {
            if (token == null || token.isEmpty()) return base;
            String t = token.toLowerCase(Locale.ROOT);
            return base.stream()
                      .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t))
                      .collect(Collectors.toList());
        }
    }

    // ====================================================
    // DATA CLASSES
    // ====================================================
    private static class LastSalaryData {
        Map<UUID, Long> lastPayments = new HashMap<>();
        long lastTaskRun = 0;
    }
}
