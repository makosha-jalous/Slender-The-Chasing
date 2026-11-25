package com.example.slenderman.client.mesh;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The real 3D model of Slenderman (the mesh from Slender: The Arrival, edited in Blockbench), body part.
 * It is read from assets/slenderman/models/entity/slender_mesh.bin (made from the .bbmodel, see the tools note in the zip).
 *
 * Plain Java, no Minecraft classes: the numbers are in the "vanilla model space" (1/16 block, y goes DOWN, the ground is at y = 24,
 * the front of the model looks to -z), the same space the classic SlendermanModel lives in.
 *
 * The mesh is skinned to 12 joints (RIG_NAMES). Every vertex has up to 4 joints with weights.
 */
public final class SlenderMesh {
    public static final int RIG = 12;
    /** the order of the joints (the same as the parts of the classic model). */
    public static final String[] RIG_NAMES = {"torso", "head", "left_arm", "left_forearm", "left_hand",
            "right_arm", "right_forearm", "right_hand", "left_thigh", "left_shin", "right_thigh", "right_shin"};

    public final int vertCount;
    public final int triCount;
    /** parent joint of every joint (-1 = none). A parent always comes before its child. */
    public final int[] parent = new int[RIG];
    /** pivot of every joint (x, y, z) in the rest pose. */
    public final float[] pivot = new float[RIG * 3];
    public final float[] pos;
    public final float[] nrm;
    public final float[] uv;
    public final byte[] bone;
    public final float[] weight;
    public final short[] idx;

    private SlenderMesh(DataInputStream in) throws IOException {
        if (in.readInt() != 0x534C4D31) throw new IOException("not a slender mesh file");
        vertCount = in.readInt();
        triCount = in.readInt();
        if (in.readInt() != RIG) throw new IOException("wrong number of joints");
        for (int i = 0; i < RIG; i++) {
            parent[i] = in.readInt();
            for (int k = 0; k < 3; k++) pivot[i * 3 + k] = in.readFloat();
        }
        pos = new float[vertCount * 3];
        nrm = new float[vertCount * 3];
        uv = new float[vertCount * 2];
        bone = new byte[vertCount * 4];
        weight = new float[vertCount * 4];
        idx = new short[triCount * 3];
        for (int v = 0; v < vertCount; v++) {
            for (int k = 0; k < 3; k++) pos[v * 3 + k] = in.readFloat();
            for (int k = 0; k < 3; k++) nrm[v * 3 + k] = in.readFloat();
            uv[v * 2] = in.readFloat();
            uv[v * 2 + 1] = in.readFloat();
            for (int k = 0; k < 4; k++) bone[v * 4 + k] = in.readByte();
            for (int k = 0; k < 4; k++) weight[v * 4 + k] = in.readFloat();
        }
        for (int i = 0; i < idx.length; i++) idx[i] = in.readShort();
    }

    public static SlenderMesh read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return new SlenderMesh(in);
        }
    }
}
