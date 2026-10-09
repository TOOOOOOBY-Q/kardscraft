package dev.invasion.kardscraft.game;

import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.KardsCraft;
import dev.invasion.kardscraft.bridge.Bridge;
import dev.invasion.kardscraft.bridge.BridgeState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns only an explicitly enabled world's board region. Mutations run on the server thread. */
public final class BoardManager {
    public static final int FLOOR_HALF = 40;
    private static final long RESULT_TIMEOUT_NS = 10_000_000_000L;
    private static final Map<Integer, CardEntity> cards = new HashMap<>();
    private static final Map<String, Long> hitCooldown = new HashMap<>();
    private static final Map<UUID, Vec3> frozenPlayers = new HashMap<>();
    private static final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    /** Players already placed at their HQ for the current match (a player joining after the match started still gets placed). */
    private static final Set<UUID> placedThisMatch = ConcurrentHashMap.newKeySet();
    /** Players who already received the starter kit in this server session. */
    private static final Set<UUID> kitGiven = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> managedEntities = ConcurrentHashMap.newKeySet();
    private static final AttackLedger attacks = new AttackLedger();
    private static volatile boolean enabled;
    private static volatile boolean frozen;
    private static boolean worldPrepared;
    private static String matchKey;
    private static String sessionKey;
    private static long ticks;
    private static long lastFeedbackTick = -100;
    private static double cardWidth = 2.0;
    private static int mySide = 1;

    private BoardManager() {}
    public static boolean isFrozen() { return enabled && frozen; }
    public static boolean isEnabled() { return enabled; }
    public static String status() {
        BridgeState state = Bridge.get();
        return "board=" + enabled + ", frozen=" + frozen + ", cards=" + cards.size()
                + ", pending=" + attacks.size() + (state == null ? ", bridge=disconnected"
                : ", status=" + state.status + ", ready=" + state.ready + ", busy=" + state.busy
                + ", geometryReady=" + state.geometryReady + ", myTurn=" + state.myTurn
                + ", match=" + state.matchId);
    }
    public static boolean isManagedLevel(Level level) { return enabled && level.dimension() == Level.OVERWORLD; }
    private static boolean inside(Vec3 p) {
        return p.x >= -FLOOR_HALF && p.x <= FLOOR_HALF + 1 && p.z >= -FLOOR_HALF
                && p.z <= FLOOR_HALF + 1 && p.y >= floorY() - 8 && p.y <= floorY() + 48;
    }
    public static boolean isManagedEntity(Entity entity) {
        return isManagedLevel(entity.level()) && (inside(entity.position())
                || participants.contains(entity.getUUID()) || managedEntities.contains(entity.getUUID()));
    }
    public static boolean shouldFreezeEntity(Entity entity) {
        return !(entity instanceof CardEntity) && isInteractionBlocked(entity);
    }
    public static boolean isInteractionBlocked(Entity entity) {
        BridgeState state = Bridge.getActive();
        return isManagedEntity(entity) && (frozen || state == null || !state.myTurn
                || !state.geometryReady || state.busy || !state.ready);
    }
    /**
     * Protected blocks: the unbreakable board base (floorY-1) and any leftover barrier at floorY/floorY-1.
     * The visible board layer itself (KardsCraft.BOARD at floorY) is deliberately breakable.
     */
    public static boolean isFloorBlock(Level level, BlockPos pos, BlockState state) {
        if (!isManagedLevel(level) || Math.abs(pos.getX()) > FLOOR_HALF || Math.abs(pos.getZ()) > FLOOR_HALF) return false;
        int y = pos.getY();
        return (y == floorY() - 1 && state.is(KardsCraft.BOARD_BASE))
                || ((y == floorY() || y == floorY() - 1) && state.is(Blocks.BARRIER));
    }
    public static int floorY() { return (int) Math.floor(Coords.floorTop()) - 1; }
    private static BoardWorldMarker worldMarker(MinecraftServer server) {
        return new BoardWorldMarker(server.getWorldPath(LevelResource.ROOT), floorY());
    }

    public static void onServerStarted(MinecraftServer server) {
        onServerStopped();
        try {
            enabled = worldMarker(server).isEnabled();
        } catch (IOException error) {
            KardsCraft.LOG.warn("Cannot read board marker; board remains disabled: {}", error.toString());
        }
        frozen = enabled;
    }

    /** Explicit opt-in; refuse occupied cells instead of replacing an existing build. */
    public static boolean enable(MinecraftServer server, ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) {
            player.sendSystemMessage(Component.literal("请先回到主世界。"));
            return false;
        }
        if (enabled) { participants.add(player.getUUID()); sendToHQ(player); return true; }
        if (!validHeight(server.overworld())) {
            player.sendSystemMessage(Component.literal("棋盘高度无效；请检查 boardY。"));
            return false;
        }
        ServerLevel level = server.overworld();
        boolean previouslyPrepared;
        try { previouslyPrepared = worldMarker(server).isPrepared(); }
        catch (IOException error) {
            player.sendSystemMessage(Component.literal("无法读取棋盘准备标记：" + error.getMessage()));
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -FLOOR_HALF; !previouslyPrepared && x <= FLOOR_HALF; x++) {
            for (int z = -FLOOR_HALF; z <= FLOOR_HALF; z++) {
                for (int y = floorY(); y <= floorY() + 3; y++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (!state.isAir() && !(y == floorY() && (state.is(Blocks.BARRIER) || state.is(KardsCraft.BOARD)))) {
                        player.sendSystemMessage(Component.literal("未启用：棋盘区域已有方块。请在新的空白专用世界使用 /kardscraft enable。"));
                        return false;
                    }
                }
            }
        }
        try {
            worldMarker(server).enable();
        } catch (IOException error) {
            player.sendSystemMessage(Component.literal("无法保存专用世界标记：" + error.getMessage()));
            return false;
        }
        enabled = true;
        frozen = true;
        prepareWorld(server);
        worldPrepared = true;
        participants.add(player.getUUID());
        Vec3 start = safeSpawn(level, new Vec3(Coords.ORIGIN_X, Coords.floorTop(), Coords.ORIGIN_Z));
        if (start != null) player.teleportTo(start.x, start.y, start.z);
        player.sendSystemMessage(Component.literal("已启用此世界的 KardsCraft 棋盘。连接训练对局后开始；/kardscraft disable 可停用。"));
        return true;
    }

    public static boolean disable(MinecraftServer server) {
        try { worldMarker(server).disable(); }
        catch (IOException error) {
            KardsCraft.LOG.warn("Could not remove board marker: {}", error.toString());
            return false;
        }
        clearCards();
        onServerStopped();
        return true;
    }
    public static void onServerStopped() {
        Bridge.clearPending();
        while (Bridge.pollResult() != null) {}
        cards.clear(); hitCooldown.clear(); frozenPlayers.clear(); participants.clear(); managedEntities.clear(); placedThisMatch.clear(); kitGiven.clear();
        attacks.clear(); enabled = false; frozen = false; worldPrepared = false;
        matchKey = null; sessionKey = null; ticks = 0; cardWidth = 2.0; lastFeedbackTick = -100;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (!enabled) { while (Bridge.pollResult() != null) {} return; }
        ServerLevel level = server.overworld();
        if (!validHeight(level)) { frozen = true; return; }
        if (!worldPrepared) { prepareWorld(server); worldPrepared = true; }
        BridgeState snapshot = Bridge.get();
        attacks.expire(System.nanoTime(), RESULT_TIMEOUT_NS);
        // The snapshot that reports the final accepted hit may already have inMatch=false.
        // Settle its confirmed counter before removing card proxies and pending attack attribution.
        if (snapshot != null && snapshot.sessionId.equals(sessionKey)
                && (!snapshot.inMatch || snapshot.matchId.equals(matchKey))) processResults(level);
        BridgeState st = snapshot != null && snapshot.usable() ? snapshot : null;
        Coords co = Coords.from(st);
        if (st == null || co == null || !st.geometryReady) {
            clearCards(); attacks.clear(); hitCooldown.clear(); matchKey = null;
            setFrozen(true);
            holdPlayers(level);
            while (Bridge.pollResult() != null) {}
            return;
        }
        mySide = st.mySide;
        boolean newMatch = !st.matchId.equals(matchKey) || !st.sessionId.equals(sessionKey);
        if (newMatch) {
            clearCards(); attacks.clear(); hitCooldown.clear();
            // A restarted backend may reuse sequence numbers; never carry its predecessor's queue forward.
            while (Bridge.pollResult() != null) {}
            matchKey = st.matchId; sessionKey = st.sessionId; cardWidth = 2.0;
            placedThisMatch.clear();
            KardsCraft.LOG.info("KardsCraft: new match {}", matchKey);
        }
        syncCards(level, st, co);
        // Every player on the board is placed at their HQ once per match, including players who join after
        // the bridge connected (previously only players present at the first tick of the match were moved).
        for (ServerPlayer player : level.players()) {
            if (placedThisMatch.contains(player.getUUID())) continue;
            if ((inside(player.position()) || participants.contains(player.getUUID())) && sendToHQ(player)) {
                placedThisMatch.add(player.getUUID());
                if (kitGiven.add(player.getUUID())) {
                    int added = giveKit(player, false);
                    if (added > 0) player.sendSystemMessage(Component.literal("已放入 " + added
                            + " 件工具：TNT + 打火石（右键点燃）、镐（右键直接破坏方块）、剑、刷怪蛋、棋盘方块。"));
                }
            }
        }
        setFrozen(!st.myTurn || st.busy || !st.ready);
        if (frozen) holdPlayers(level);
        else { keepOnBoard(level); manageMobs(level); }
        if (ticks % 200 == 0) hitCooldown.values().removeIf(t -> ticks - t > 200);
    }

    private static boolean validHeight(ServerLevel level) {
        return Double.isFinite(Config.boardY) && floorY() >= level.getMinBuildHeight()
                && floorY() + 48 < level.getMaxBuildHeight();
    }
    /**
     * Layout (board-layer-v2): a visible, breakable board layer at floorY and an unbreakable base at floorY-1.
     * The board layer is laid exactly once per world (old barrier floors are migrated) and never refilled, so
     * craters persist. The base only ever replaces air/barrier and cannot be destroyed, so nobody falls through.
     */
    private static void prepareWorld(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BoardWorldMarker marker = worldMarker(server);
        boolean layoutDone;
        try { layoutDone = marker.isLayoutDone(); }
        catch (IOException error) {
            // Unknown state: do not lay the board (that could fill craters); the base still guarantees safety.
            KardsCraft.LOG.warn("Cannot read board layout marker: {}", error.toString());
            layoutDone = true;
        }
        BlockState board = KardsCraft.BOARD.defaultBlockState(), base = KardsCraft.BOARD_BASE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int laid = 0, bases = 0;
        for (int x = -FLOOR_HALF; x <= FLOOR_HALF; x++) {
            for (int z = -FLOOR_HALF; z <= FLOOR_HALF; z++) {
                BlockState below = level.getBlockState(pos.set(x, floorY() - 1, z));
                if (below.isAir() || below.is(Blocks.BARRIER)) { level.setBlock(pos, base, Block.UPDATE_CLIENTS); bases++; }
                if (layoutDone) continue;
                BlockState top = level.getBlockState(pos.set(x, floorY(), z));
                if (top.isAir() || top.is(Blocks.BARRIER)) { level.setBlock(pos, board, Block.UPDATE_CLIENTS); laid++; }
            }
        }
        if (!layoutDone) {
            try { marker.markLayoutDone(); }
            catch (IOException error) { KardsCraft.LOG.warn("Cannot save board layout marker: {}", error.toString()); }
        }
        KardsCraft.LOG.info("KardsCraft: board prepared at y={} (board blocks laid {}, base blocks {}, layout already done {})",
                floorY(), laid, bases, layoutDone);
        // Never change global gamerules, weather, spawn points or other dimensions.
    }
    private static void syncCards(ServerLevel level, BridgeState st, Coords co) {
        cardWidth = estimateCardWidth(st, co);
        Set<Integer> seen = new HashSet<>();
        for (BridgeState.Card c : st.cards) {
            if (c.def <= 0 || !isBoardCard(c)) continue;
            Vec3 p = co.toMcOnFloor(c.x, c.y, c.z);
            if (!inside(p)) continue;
            seen.add(c.id);
            CardEntity entity = cards.get(c.id);
            if (entity == null || entity.isRemoved()) {
                entity = KardsCraft.CARD.create(level);
                if (entity == null) continue;
                entity.moveTo(p.x, p.y, p.z, 0f, 0f);
                entity.setCardData(c);
                level.addFreshEntity(entity);
                cards.put(c.id, entity);
            } else {
                if (entity.position().distanceToSqr(p) > 0.0025) entity.teleportTo(p.x, p.y, p.z);
                entity.setCardData(c);
            }
            AttributeInstance scale = entity.getAttribute(Attributes.SCALE);
            double width = c.hq ? cardWidth * 1.5 : cardWidth;
            if (scale != null && Math.abs(scale.getBaseValue() - width) > 0.01) scale.setBaseValue(width);
        }
        Iterator<Map.Entry<Integer, CardEntity>> iterator = cards.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, CardEntity> entry = iterator.next();
            if (!seen.contains(entry.getKey())) { entry.getValue().discard(); iterator.remove(); }
        }
    }
    private static boolean isBoardCard(BridgeState.Card c) {
        return c.hq || c.loc == BridgeState.LOC_SUPPORT_LEFT || c.loc == BridgeState.LOC_SUPPORT_RIGHT
                || c.loc == BridgeState.LOC_FRONTLINE;
    }
    private static double estimateCardWidth(BridgeState st, Coords co) {
        double best = Double.MAX_VALUE;
        List<BridgeState.Card> list = st.cards;
        for (int i = 0; i < list.size(); i++) {
            BridgeState.Card a = list.get(i);
            if (a.hq || !isBoardCard(a)) continue;
            for (int j = i + 1; j < list.size(); j++) {
                BridgeState.Card b = list.get(j);
                if (b.hq || a.loc != b.loc || !isBoardCard(b)) continue;
                double distance = Math.hypot(a.x - b.x, a.y - b.y) * co.scale;
                if (distance > 0.2) best = Math.min(best, distance);
            }
        }
        return best == Double.MAX_VALUE ? cardWidth : Math.max(0.8, Math.min(5.0, best * 0.8));
    }
    private static void clearCards() { cards.values().forEach(Entity::discard); cards.clear(); }
    /** @return true if the player was moved next to their HQ. */
    public static boolean sendToHQ(ServerPlayer player) {
        if (!isManagedLevel(player.level())) return false;
        CardEntity hq = cards.values().stream().filter(c -> c.isHeadquarters() && c.getSide() == mySide).findFirst().orElse(null);
        if (hq == null) return false;
        Vec3 h = hq.position();
        Vec3 direction = new Vec3(Coords.ORIGIN_X - h.x, 0, Coords.ORIGIN_Z - h.z).normalize();
        Vec3 at = safeSpawn(player.serverLevel(), h.add(direction.scale(cardWidth * 1.2)));
        if (at == null) {
            player.sendSystemMessage(Component.literal("总部附近没有可站立的空位；请先清理方块。"));
            return false;
        }
        participants.add(player.getUUID());
        player.teleportTo(at.x, at.y, at.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        frozenPlayers.remove(player.getUUID());
        KardsCraft.LOG.info("KardsCraft: {} placed next to HQ #{} at ({}, {}, {})",player.getGameProfile().getName(),
                hq.getCardId(),Math.round(at.x*10)/10.0,Math.round(at.y*10)/10.0,Math.round(at.z*10)/10.0);
        return true;
    }
    private static Vec3 safeSpawn(ServerLevel level, Vec3 preferred) {
        int px = (int) Math.floor(preferred.x), pz = (int) Math.floor(preferred.z);
        for (int radius = 0; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = px + dx, z = pz + dz;
                    if (Math.abs(x) > FLOOR_HALF || Math.abs(z) > FLOOR_HALF) continue;
                    BlockPos feet = new BlockPos(x, floorY() + 1, z);
                    if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                            && !level.getBlockState(feet.below()).isAir())
                        return new Vec3(x + 0.5, feet.getY(), z + 0.5);
                }
            }
        }
        // Everything nearby was blown away: repair a single board block under the preferred spot rather than
        // leaving the player without a respawn point (the base below keeps holes shallow anyway).
        if (Math.abs(px) > FLOOR_HALF || Math.abs(pz) > FLOOR_HALF) return null;
        BlockPos feet = new BlockPos(px, floorY() + 1, pz);
        if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) return null;
        if (level.getBlockState(feet.below()).isAir()) {
            level.setBlock(feet.below(), KardsCraft.BOARD.defaultBlockState(), Block.UPDATE_ALL);
            KardsCraft.LOG.info("KardsCraft: repaired one board block at ({}, {}, {}) for a safe respawn", px, floorY(), pz);
        }
        return new Vec3(px + 0.5, feet.getY(), pz + 0.5);
    }

    /** Starter kit; only adds item types the player does not already carry. @return number of stacks added. */
    public static int giveKit(ServerPlayer player, boolean announce) {
        Object[][] kit = {
                {Items.DIAMOND_SWORD, 1}, {Items.IRON_PICKAXE, 1}, {Items.TNT, 16}, {Items.FLINT_AND_STEEL, 1},
                {KardsCraft.BOARD_ITEM, 32}, {Items.CREEPER_SPAWN_EGG, 8}, {Items.ZOMBIE_SPAWN_EGG, 8},
                {Items.SKELETON_SPAWN_EGG, 8}, {Items.OAK_PLANKS, 64}};
        int added = 0;
        for (Object[] entry : kit) {
            Item item = (Item) entry[0];
            if (player.getInventory().contains(stack -> stack.is(item))) continue;
            if (player.getInventory().add(new ItemStack(item, (Integer) entry[1]))) added++;
        }
        if (added > 0) player.containerMenu.broadcastChanges();
        if (announce) KardsCraft.LOG.info("KardsCraft: kit for {} ({} stacks)", player.getGameProfile().getName(), added);
        return added;
    }

    /**
     * Right-click with any pickaxe breaks the targeted block at once. Other right-click actions are untouched:
     * blocks in hand place, flint and steel ignites TNT, spawn eggs spawn, doors/buttons are used.
     * Runs after the turn gate; the unbreakable base, leftover barriers and the turn gate are still enforced here
     * and again by PlayerBlockBreakEvents.BEFORE inside destroyBlock.
     */
    public static InteractionResult rightClickBreak(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!isManagedLevel(level) || player.isSpectator() || !player.getItemInHand(hand).is(ItemTags.PICKAXES))
            return InteractionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return InteractionResult.PASS;
        if (isInteractionBlocked(player) || isFloorBlock(level, pos, state)
                || (state.getDestroySpeed(level, pos) < 0 && !player.isCreative())) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer serverPlayer) {
            boolean broken = serverPlayer.gameMode.destroyBlock(pos);
            if (broken) KardsCraft.LOG.debug("KardsCraft: right-click break {} at {}", state.getBlock(), pos);
        }
        return InteractionResult.SUCCESS;
    }
    public static void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        if (participants.contains(oldPlayer.getUUID())) sendToHQ(newPlayer);
    }

    static boolean onCardHit(CardEntity card, DamageSource source, float amount) {
        BridgeState state = Bridge.getActive();
        if (!enabled || frozen || state == null || !state.myTurn || !state.matchId.equals(matchKey)
                || card.getCardId() < 0 || cards.get(card.getCardId()) != card || !Float.isFinite(amount) || amount <= 0) return false;
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        boolean explosion = source.is(DamageTypeTags.IS_EXPLOSION);
        if (!explosion && !(attacker instanceof LivingEntity)) return false;
        if (attacker instanceof CardEntity) return false;
        boolean melee = direct == attacker && (attacker instanceof Player || attacker instanceof Mob);
        int damage = explosion ? Math.max(1, Config.explosionDamage)
                : melee && attacker instanceof Player player ? weaponDamage(player.getMainHandItem())
                : Math.max(1, Math.round(amount / 2f));
        String key = card.getCardId() + "|" + (direct != null ? direct.getUUID() : "environment");
        int cooldown = explosion ? 5 : Math.max(1, Config.hitCooldownTicks);
        Long last = hitCooldown.get(key);
        if (last != null && ticks - last < cooldown) return false;
        int sequence = Bridge.sendDamage(card.getCardId(), damage);
        if (sequence < 0) { feedback(card.level(), "伤害请求未提交：桥接尚未允许本回合操作。"); return false; }
        hitCooldown.put(key, ticks);
        attacks.add(sequence, new AttackLedger.Attack(attacker == null ? null : attacker.getUUID(),
                card.getCardId(), state.matchId, melee && !card.isHeadquarters(), System.nanoTime()));
        return true;
    }
    private static void processResults(ServerLevel level) {
        BridgeState.Result result;
        while ((result = Bridge.pollResult()) != null) {
            AttackLedger.Attack attack = attacks.resolve(result.seq, result.matchId);
            if (attack == null) continue;
            if (!result.accepted) { feedback(level, "KARDS 拒绝伤害：" + result.reason); continue; }
            if (!attack.melee() || result.damage <= 0 || result.attack <= 0 || attack.attacker() == null) continue;
            Entity entity = level.getEntity(attack.attacker());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            double hearts = Math.max(0, Math.min(1000, Config.counterHeartsPerAttack));
            float counter = (float) Math.min(2048, result.attack * hearts * 2);
            if (!Float.isFinite(counter) || counter <= 0) continue;
            DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(KardsCraft.COUNTER_DAMAGE), cards.get(attack.cardId()));
            living.hurt(source, counter);
            // Player simulation is paused on an enemy/terminal tick, including its usual health sync.
            if (living instanceof ServerPlayer player) player.connection.send(new ClientboundSetHealthPacket(
                    player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
        }
    }
    private static void feedback(Level level, String message) {
        if (ticks - lastFeedbackTick < 40) return;
        lastFeedbackTick = ticks;
        for (Player player : level.players()) if (isManagedEntity(player)) player.sendSystemMessage(Component.literal(message));
        KardsCraft.LOG.info("{}", message);
    }
    public static int weaponDamage(ItemStack stack) {
        if (stack.is(Items.NETHERITE_SWORD)) return 4;
        if (stack.is(Items.DIAMOND_SWORD)) return 3;
        if (stack.is(Items.IRON_SWORD)) return 2;
        return 1;
    }
    private static AABB area() {
        return new AABB(-FLOOR_HALF, floorY() - 8, -FLOOR_HALF, FLOOR_HALF + 1, floorY() + 48, FLOOR_HALF + 1);
    }
    public static LivingEntity boardTarget(Mob mob, LivingEntity proposed) {
        if (!(mob instanceof Monster) || !isManagedEntity(mob)) return proposed;
        CardEntity best = null;
        double distance = Double.MAX_VALUE;
        int enemy = mySide == 1 ? 2 : 1;
        for (CardEntity card : cards.values()) {
            if (card.getSide() != enemy || card.isRemoved()) continue;
            double d = card.distanceToSqr(mob);
            if (d < distance) { distance = d; best = card; }
        }
        return best;
    }
    private static void manageMobs(ServerLevel level) {
        for (Entity entity : level.getEntities((Entity) null, area(), e -> !(e instanceof CardEntity) && !(e instanceof Player)))
            managedEntities.add(entity.getUUID());
        for (Monster mob : level.getEntitiesOfClass(Monster.class, area())) {
            LivingEntity best = boardTarget(mob, null);
            mob.setTarget(best);
            if (best == null) { mob.getNavigation().stop(); if (mob instanceof Creeper creeper) creeper.setSwellDir(-1); }
            else if (ticks % 10 == 0) mob.getNavigation().moveTo(best, 1.0);
        }
        if (ticks % 200 == 0) managedEntities.removeIf(id -> level.getEntity(id) == null);
    }
    private static void setFrozen(boolean value) {
        if (value != frozen) frozenPlayers.clear();
        frozen = value;
    }
    private static void holdPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (!isManagedEntity(player)) continue;
            Vec3 at = frozenPlayers.computeIfAbsent(player.getUUID(), id -> player.position());
            if (player.position().distanceToSqr(at) > 0.001) player.teleportTo(at.x, at.y, at.z);
        }
    }
    private static void keepOnBoard(ServerLevel level) {
        for (ServerPlayer player : level.players())
            if (participants.contains(player.getUUID()) && !inside(player.position())) sendToHQ(player);
    }
}
