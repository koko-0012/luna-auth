package xyz.nikitacartes.easyauth.utils;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

public class LastLocation {

    public ServerWorld dimension;
    public Vec3d position;
    public float yaw;
    public float pitch;

    public String toString() {
        return String.format("LastLocation{dimension=%s, position=%s, yaw=%s, pitch=%s}", dimension, position,
                yaw, pitch);
    }
}
