package nl.teamdiopside.expandingtechnologies.blocks.kinetic_battery;

import com.jozufozu.flywheel.util.transform.TransformStack;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.content.kinetics.BlockStressValues;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.particle.AirParticleData;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public class KineticBatteryBlockEntity extends GeneratingKineticBlockEntity {

    public double chargingStressBase = 64; // TODO
    public int maxStoredRotations = 1024; // TODO
    public int rotationTickCounter = 0;
    public float previousSpeed = 0;
    public int storedRotations = 0;
    public ScrollValueBehaviour generatedSpeed;

    public KineticBatteryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public boolean isCharging() {
        return this.getBlockState().getValue(KineticBatteryBlock.CHARGING);
    }

    public void setCharging(boolean charging) {
        if (level == null) return;
        level.setBlockAndUpdate(getBlockPos(), this.getBlockState().setValue(KineticBatteryBlock.CHARGING, charging));
    }

    public int getStoredRotations() {
        return storedRotations;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        int max = CreativeMotorBlockEntity.MAX_SPEED;
        generatedSpeed = new KineticScrollValueBehaviour(Lang.translateDirect("kinetics.creative_motor.rotation_speed"), this, new SpeedBox());
        generatedSpeed.between(-max, max);
        generatedSpeed.value = CreativeMotorBlockEntity.DEFAULT_SPEED;
        generatedSpeed.withCallback(i -> this.updateGeneratedRotation());
        behaviours.add(generatedSpeed);
    }

    @Override
    public void initialize() {
        super.initialize();
        setCharging(hasSource() && getGeneratedSpeed() < getTheoreticalSpeed() || storedRotations <= 0);
        if (!isCharging())
            updateGeneratedRotation();
    }

    @Override
    protected Block getStressConfigKey() {
        return AllBlocks.HAND_CRANK.get();
    }

    @Override
    public float getGeneratedSpeed() {
        if (isCharging() || storedRotations <= 0)
            return 0;
        return convertToDirection(generatedSpeed.getValue(), getBlockState().getValue(KineticBatteryBlock.FACING));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (isCharging()) {
            return false;
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        compound.putInt("StoredRotations", storedRotations);
        super.write(compound, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        storedRotations = compound.getInt("StoredRotations");
    }

    @Override
    public void tick() {
        super.tick();
        if (isCharging() && (getSpeed() == 0 || storedRotations >= maxStoredRotations)) return;
        if (!isCharging() && (storedRotations == 0 || hasSource()))  {
            // If rotations are empty or a faster source has been added to the network, force charging
            setCharging(true);
            updateGeneratedRotation();
        }

        // Calculate ticks per revolution
        float speed = Math.abs(getSpeed());
        if (speed == 0) return;
        if (Math.abs(speed - previousSpeed) > 0.001f) rotationTickCounter = 0;
        previousSpeed = speed;
        float tpr = 1200 / speed; // Ticks per revolution
        rotationTickCounter++;
        if (rotationTickCounter < tpr) return;

        // Add charging particles
        if (level != null && level.isClientSide && isCharging() && storedRotations != maxStoredRotations) {
            Vec3 centerOf = VecHelper.getCenterOf(worldPosition);
            Vec3 v = VecHelper.offsetRandomly(centerOf, level.random, .65f);
            Vec3 m = centerOf.subtract(v);
            level.addParticle(DustParticleOptions.REDSTONE, v.x, v.y, v.z, m.x, m.y, m.z);
        }

        // Charge / Discharge when rotated and reset tick counter
        if (isCharging()) {
            storedRotations = Math.min(maxStoredRotations, storedRotations + 1);
        } else {
            storedRotations = Math.max(0, storedRotations - 1);
        }
        rotationTickCounter = 0;
    }

    static class SpeedBox extends ValueBoxTransform.Sided {

        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 12.5);
        }

        @Override
        public Vec3 getLocalOffset(BlockState state) {
            Direction facing = state.getValue(KineticBatteryBlock.FACING);
            return super.getLocalOffset(state).add(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(-1 / 16f));
        }

        @Override
        public void rotate(BlockState state, PoseStack ms) {
            super.rotate(state, ms);
            Direction facing = state.getValue(KineticBatteryBlock.FACING);
            if (facing.getAxis() == Direction.Axis.Y)
                return;
            if (getSide() != Direction.UP)
                return;
            TransformStack.cast(ms)
                    .rotateZ(-AngleHelper.horizontalAngle(facing) + 180);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            Direction facing = state.getValue(KineticBatteryBlock.FACING);
            boolean charging = state.getValue(KineticBatteryBlock.CHARGING);
            if (facing.getAxis() != Direction.Axis.Y && direction == Direction.DOWN || charging)
                return false;
            return direction.getAxis() != facing.getAxis();
        }
    }
}
