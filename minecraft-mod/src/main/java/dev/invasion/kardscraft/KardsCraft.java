package dev.invasion.kardscraft;

import dev.invasion.kardscraft.bridge.Bridge;
import dev.invasion.kardscraft.game.BoardBlock;
import dev.invasion.kardscraft.game.BoardManager;
import dev.invasion.kardscraft.game.CardEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KardsCraft implements ModInitializer {
    public static final String MOD_ID = "kardscraft";
    public static final Logger LOG = LoggerFactory.getLogger("KardsCraft");
    public static volatile String CLIENT_DIAGNOSTICS = "客户端画面模块尚未初始化";

    public static final EntityType<CardEntity> CARD = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            id("card"),
            EntityType.Builder.<CardEntity>of(CardEntity::new, MobCategory.MISC)
                    .sized(1.0F, 0.7F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build("card"));

    /** Visible, breakable board surface (see BoardBlock). Wood-like hardness: TNT blasts it, a pickaxe breaks it quickly.
     *  Like the old barrier floor, nothing spawns on it naturally (only spawn eggs), so wild mobs never attack cards. */
    public static final BoardBlock BOARD = Registry.register(BuiltInRegistries.BLOCK, id("board"),
            new BoardBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.8F, 1.0F)
                    .sound(SoundType.WOOD).isValidSpawn((state, level, pos, type) -> false)));
    /**
     * Unbreakable crater bottom one block below the board (replaces the old invisible barrier floor). Always drawn:
     * under an intact board its top face is culled, so over the real KARDS table only craters show up as dark patches.
     */
    public static final Block BOARD_BASE = Registry.register(BuiltInRegistries.BLOCK, id("board_base"),
            new Block(BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).strength(-1.0F, 3600000.0F)
                    .noLootTable().sound(SoundType.DEEPSLATE_TILES).isValidSpawn((state, level, pos, type) -> false)));
    public static final Item BOARD_ITEM = Registry.register(BuiltInRegistries.ITEM, id("board"),
            new BlockItem(BOARD, new Item.Properties()));

    /** Counter-attack damage; tagged bypasses_invulnerability so it also hurts creative players. */
    public static final ResourceKey<DamageType> COUNTER_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, id("counter"));

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        Config.load();
        FabricDefaultAttributeRegistry.register(CARD, LivingEntity.createLivingAttributes());
        Bridge.start();

        // Gate entity updates before this tick's simulation, not after it has already caused damage.
        ServerTickEvents.START_SERVER_TICK.register(BoardManager::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(BoardManager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BoardManager.onServerStopped());
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> BoardManager.onRespawn(oldPlayer, newPlayer));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("kardscraft").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(BoardManager.status()
                                    + "\n画面：" + CLIENT_DIAGNOSTICS), false);
                            return 1;
                        }))
                        .then(Commands.literal("enable").executes(context -> BoardManager.enable(
                                context.getSource().getServer(), context.getSource().getPlayerOrException()) ? 1 : 0))
                        .then(Commands.literal("kit").executes(context -> {
                            int added = BoardManager.giveKit(context.getSource().getPlayerOrException(), true);
                            context.getSource().sendSuccess(() -> Component.literal(added > 0
                                    ? "已放入 " + added + " 件工具（只填空的快捷栏格子）。" : "快捷栏没有空位，或工具都已经有了。"), false);
                            return 1;
                        }))
                        .then(Commands.literal("disable").executes(context -> {
                            boolean disabled = BoardManager.disable(context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(disabled
                                    ? "KardsCraft 已停用；已放置的方块和棋盘保留。" : "停用失败，请查看日志。"), false);
                            return disabled ? 1 : 0;
                        }))));

        // The unbreakable board base and safety barriers must not be broken (the visible board layer may be).
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !BoardManager.isFloorBlock(level, pos, state) && !BoardManager.isInteractionBlocked(player));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                BoardManager.isFloorBlock(level, pos, level.getBlockState(pos)) || BoardManager.isInteractionBlocked(player)
                        ? InteractionResult.FAIL : InteractionResult.PASS);

        // During the enemy's turn Steve can't do anything.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                BoardManager.isInteractionBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                BoardManager.isInteractionBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Right-click with a pickaxe breaks the block (vanilla pickaxes have no right-click action, so this never
        // collides with placing, igniting with flint and steel, or spawn eggs). Runs after the turn gate above.
        UseBlockCallback.EVENT.register(BoardManager::rightClickBreak);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                BoardManager.isInteractionBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
                BoardManager.isInteractionBlocked(player) ? InteractionResultHolder.fail(player.getItemInHand(hand))
                        : InteractionResultHolder.pass(player.getItemInHand(hand)));

        LOG.info("KardsCraft loaded");
    }
}
