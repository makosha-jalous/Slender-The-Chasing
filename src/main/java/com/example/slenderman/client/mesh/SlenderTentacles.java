package com.example.slenderman.client.mesh;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The 4 tentacles of the mesh (assets/slenderman/models/entity/slender_tentacles.bin), same model space as {@link SlenderMesh}.
 * Every tentacle has a centre line of JOINTS points (from the root outwards); every vertex knows where it is along the tentacle (s = 0..1).
 */
public final class SlenderTentacles {
    public final int count;
    public final int joints;
    /** the root bone of the tentacles on the back (not used for drawing). */
    public final float[] root = new float[3];
    public final int[] vertCount;
    public final int[] triCount;
    /** [tentacle][joint * 3 + axis] rest position of the centre line. */
    public final float[][] joint;
    public final float[][] pos;
    public final float[][] nrm;
    public final float[][] along;
    public final short[][] idx;

    private SlenderTentacles(DataInputStream in) throws IOException {
        if (in.readInt() != 0x534C5431) throw new IOException("not a slender tentacle file");
        count = in.readInt();
        joints = in.readInt();
        for (int k = 0; k < 3; k++) root[k] = in.readFloat();
        vertCount = new int[count];
        triCount = new int[count];
        joint = new float[count][];
        pos = new float[count][];
        nrm = new float[count][];
        along = new float[count][];
        idx = new short[count][];
        for (int t = 0; t < count; t++) {
            vertCount[t] = in.readInt();
            triCount[t] = in.readInt();
            joint[t] = new float[joints * 3];
            for (int i = 0; i < joint[t].length; i++) joint[t][i] = in.readFloat();
            pos[t] = new float[vertCount[t] * 3];
            nrm[t] = new float[vertCount[t] * 3];
            along[t] = new float[vertCount[t]];
            for (int v = 0; v < vertCount[t]; v++) {
                for (int k = 0; k < 3; k++) pos[t][v * 3 + k] = in.readFloat();
                for (int k = 0; k < 3; k++) nrm[t][v * 3 + k] = in.readFloat();
                along[t][v] = in.readFloat();
            }
            idx[t] = new short[triCount[t] * 3];
            for (int i = 0; i < idx[t].length; i++) idx[t][i] = in.readShort();
        }
    }

    public static SlenderTentacles read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return new SlenderTentacles(in);
        }
    }
}
