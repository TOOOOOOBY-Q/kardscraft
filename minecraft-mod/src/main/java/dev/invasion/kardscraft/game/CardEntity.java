package dev.invasion.kardscraft.game;

import dev.invasion.kardscraft.bridge.BridgeState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** A nonpersistent hit proxy. KARDS remains the sole authority for card life/death. */
public class CardEntity extends LivingEntity {
    private static final EntityDataAccessor<Integer> CARD_ID = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SIDE = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTACK = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEFENSE = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HQ = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.STRING);

    public CardEntity(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CARD_ID, -1);
        builder.define(SIDE, 0);
        builder.define(ATTACK, 0);
        builder.define(DEFENSE, 0);
        builder.define(HQ, false);
        builder.define(NAME, "");
    }

    public void setCardData(BridgeState.Card card) {
        entityData.set(CARD_ID, card.id);
        entityData.set(SIDE, card.side);
        entityData.set(ATTACK, card.atk);
        entityData.set(DEFENSE, card.def);
        entityData.set(HQ, card.hq);
        entityData.set(NAME, card.name);
    }

    public int getCardId() { return entityData.get(CARD_ID); }
    public int getSide() { return entityData.get(SIDE); }
    public int getAttack() { return entityData.get(ATTACK); }
    public int getDefense() { return entityData.get(DEFENSE); }
    public boolean isHeadquarters() { return entityData.get(HQ); }
    public String getCardName() { return entityData.get(NAME); }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        // Administrative /kill may remove a proxy; native board snapshots recreate it if still alive.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
        return BoardManager.onCardHit(this, source, amount);
    }

    @Override public void tick() { super.tick(); setDeltaMovement(Vec3.ZERO); }
    @Override public boolean isPushable() { return false; }
    @Override protected void doPush(Entity entity) {}
    @Override public void knockback(double strength, double x, double z) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public Iterable<ItemStack> getArmorSlots() { return List.of(); }
    @Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
    @Override public void setItemSlot(EquipmentSlot slot, ItemStack stack) {}
    @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
}
