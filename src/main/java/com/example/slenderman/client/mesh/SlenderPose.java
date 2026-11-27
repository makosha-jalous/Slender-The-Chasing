package com.example.slenderman.client.mesh;

/**
 * Poses and skins the mesh. No Minecraft classes here (so it can be tested alone).
 *
 * 1. setJoints(rotations): every joint of the rig turns around its pivot, a child follows its parent
 *    (exactly like the ModelParts of the classic model: the same xRot / yRot / zRot numbers are used).
 * 2. skinBody(): linear blend skinning of all the vertices, so the elbows, knees and the neck bend smoothly (no cracks).
 * 3. poseTentacles(): the 4 tentacles grow out of the back (a small copy fans out of the back and gets bigger),
 *    then they writhe slowly: every joint of the chain swings a bit, with a delay to the joint before it (a snake).
 *
 * A "matrix" is float[12]: 9 numbers of the rotation (row by row) and then x, y, z of the shift.
 */
public final class SlenderPose {
    private final SlenderMesh mesh;
    /** skin matrices of the rig joints. */
    private final float[][] skin = new float[SlenderMesh.RIG][12];
    private final float[][] global = new float[SlenderMesh.RIG][12];

    // tentacles
    private final SlenderTentacles tent;
    private final float[][] tentSkin;          // [tentacle][joint*12]... (flattened per tentacle)
    private final float[][] outPos;
    private final float[][] outNrm;
    private boolean tentVisible;

    public SlenderPose(SlenderMesh mesh, SlenderTentacles tent) {
        this.mesh = mesh;
        this.tent = tent;
        if (tent != null) {
            tentSkin = new float[tent.count][tent.joints * 12];
            outPos = new float[tent.count][];
            outNrm = new float[tent.count][];
            for (int t = 0; t < tent.count; t++) {
                outPos[t] = new float[tent.vertCount[t] * 3];
                outNrm[t] = new float[tent.vertCount[t] * 3];
            }
        } else {
            tentSkin = null;
            outPos = null;
            outNrm = null;
        }
    }

    // ------------------------------------------------------------------ small matrix helpers
    static void identity(float[] m) {
        for (int i = 0; i < 12; i++) m[i] = 0F;
        m[0] = m[4] = m[8] = 1F;
    }

    /** r = a * b (b is applied first). r may not be a or b. */
    static void mul(float[] a, float[] b, float[] r) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
            r[9 + i] = a[i * 3] * b[9] + a[i * 3 + 1] * b[10] + a[i * 3 + 2] * b[11] + a[9 + i];
        }
    }

    static void translation(float x, float y, float z, float[] m) {
        identity(m);
        m[9] = x;
        m[10] = y;
        m[11] = z;
    }

    /** the rotation of a ModelPart: first around x, then y, then z (R = Rz * Ry * Rx). */
    static void euler(float xr, float yr, float zr, float[] m) {
        float sx = (float) Math.sin(xr), cx = (float) Math.cos(xr);
        float sy = (float) Math.sin(yr), cy = (float) Math.cos(yr);
        float sz = (float) Math.sin(zr), cz = (float) Math.cos(zr);
        m[0] = cz * cy;
        m[1] = cz * sy * sx - sz * cx;
        m[2] = cz * sy * cx + sz * sx;
        m[3] = sz * cy;
        m[4] = sz * sy * sx + cz * cx;
        m[5] = sz * sy * cx - cz * sx;
        m[6] = -sy;
        m[7] = cy * sx;
        m[8] = cy * cx;
        m[9] = m[10] = m[11] = 0F;
    }

    /** rotation of an angle around a unit axis (Rodrigues). */
    static void axisAngle(float ax, float ay, float az, float angle, float[] m) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle), t = 1F - c;
        m[0] = t * ax * ax + c;
        m[1] = t * ax * ay - s * az;
        m[2] = t * ax * az + s * ay;
        m[3] = t * ax * ay + s * az;
        m[4] = t * ay * ay + c;
        m[5] = t * ay * az - s * ax;
        m[6] = t * ax * az - s * ay;
        m[7] = t * ay * az + s * ax;
        m[8] = t * az * az + c;
        m[9] = m[10] = m[11] = 0F;
    }

    // ------------------------------------------------------------------ the rig
    /**
     * @param rot 3 numbers per joint (xRot, yRot, zRot in radians), the order of SlenderMesh.RIG_NAMES
     */
    public void setJoints(float[] rot) {
        float[] r = new float[12], t = new float[12], tmp = new float[12], tmp2 = new float[12];
        for (int i = 0; i < SlenderMesh.RIG; i++) {
            int p = mesh.parent[i];
            float px = mesh.pivot[i * 3], py = mesh.pivot[i * 3 + 1], pz = mesh.pivot[i * 3 + 2];
            float ox = px, oy = py, oz = pz;
            if (p >= 0) {
                ox -= mesh.pivot[p * 3];
                oy -= mesh.pivot[p * 3 + 1];
                oz -= mesh.pivot[p * 3 + 2];
            }
            euler(rot[i * 3], rot[i * 3 + 1], rot[i * 3 + 2], r);
            translation(ox, oy, oz, t);
            mul(t, r, tmp);                       // T(offset) * R
            if (p >= 0) mul(global[p], tmp, global[i]);
            else System.arraycopy(tmp, 0, global[i], 0, 12);
            translation(-px, -py, -pz, t);
            mul(global[i], t, tmp2);              // G * T(-pivot)
            System.arraycopy(tmp2, 0, skin[i], 0, 12);
        }
    }

    public void skinBody(float[] outPos, float[] outNrm) {
        final float[] p = mesh.pos, n = mesh.nrm, w = mesh.weight;
        final byte[] b = mesh.bone;
        for (int v = 0; v < mesh.vertCount; v++) {
            float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
            float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
            for (int k = 0; k < 4; k++) {
                float wk = w[v * 4 + k];
                if (wk <= 0F) continue;
                float[] m = skin[b[v * 4 + k]];
                ox += wk * (m[0] * x + m[1] * y + m[2] * z + m[9]);
                oy += wk * (m[3] * x + m[4] * y + m[5] * z + m[10]);
                oz += wk * (m[6] * x + m[7] * y + m[8] * z + m[11]);
                onx += wk * (m[0] * nx + m[1] * ny + m[2] * nz);
                ony += wk * (m[3] * nx + m[4] * ny + m[5] * nz);
                onz += wk * (m[6] * nx + m[7] * ny + m[8] * nz);
            }
            float len = (float) Math.sqrt(onx * onx + ony * ony + onz * onz);
            if (len > 1e-6F) {
                onx /= len;
                ony /= len;
                onz /= len;
            }
            outPos[v * 3] = ox;
            outPos[v * 3 + 1] = oy;
            outPos[v * 3 + 2] = oz;
            outNrm[v * 3] = onx;
            outNrm[v * 3 + 1] = ony;
            outNrm[v * 3 + 2] = onz;
        }
    }

    // ------------------------------------------------------------------ the tentacles
    public boolean tentaclesVisible() {
        return tentVisible;
    }

    public float[] tentaclePos(int t) {
        return outPos[t];
    }

    public float[] tentacleNormals(int t) {
        return outNrm[t];
    }

    private static float clamp01(float v) {
        return v < 0F ? 0F : Math.min(v, 1F);
    }

    /**
     * @param amount 0..1 the same number as the old model used (0 = none, they start to come out at 20 % of the sanity lost)
     * @param age    ageInTicks (the writhing is slow and goes on all the time)
     */
    public void poseTentacles(float amount, float age) {
        poseTentacles(amount, age, skin[0]);
    }

    /**
     * The skin matrix that puts the mesh tentacles on the torso of the CLASSIC (cube) model.
     * A point of the mesh model is moved like this: to the classic torso pivot + (the torso rotation) * (point - attach),
     * where attach = the mesh torso pivot moved by (shiftX, shiftY, shiftZ) (the classic back is shallower than the mesh back).
     *
     * @param out float[12]
     */
    public static void classicTorsoMatrix(float pivotX, float pivotY, float pivotZ,
                                          float xRot, float yRot, float zRot,
                                          float attachX, float attachY, float attachZ, float[] out) {
        float[] r = new float[12], t = new float[12], a = new float[12];
        euler(xRot, yRot, zRot, r);
        translation(pivotX, pivotY, pivotZ, t);
        mul(t, r, a);
        translation(-attachX, -attachY, -attachZ, t);
        mul(a, t, out);
    }

    /** @param torso the skin matrix of the torso (float[12]); the mesh model uses its own, the classic model {@link #classicTorsoMatrix} */
    public void poseTentacles(float amount, float age, float[] torso) {
        if (tent == null) {
            tentVisible = false;
            return;
        }
        tentVisible = amount > 0.02F;
        if (!tentVisible) return;
        final int J = tent.joints;
        float grow = clamp01(amount);
        float e = grow * grow * (3F - 2F * grow);
        float emerge = clamp01(e / 0.35F);                   // phase 1: they swing out of the back, backwards
        float fan = clamp01((e - 0.35F) / 0.65F);            // phase 2: they fan out to their real shape
        float sizeT = clamp01(e / 0.9F);
        float k = 0.10F + 0.90F * sizeT * sizeT * (3F - 2F * sizeT);   // a small copy grows to the real size
        float[] a = new float[12], b = new float[12], c = new float[12], d = new float[12], t = new float[12], g = new float[12];
        for (int ti = 0; ti < tent.count; ti++) {
            float[] jr = tent.joint[ti];
            float c0x = jr[0], c0y = jr[1], c0z = jr[2];
            // direction of the first part of the tentacle and the "backwards" direction (+z is the back, -y is up)
            float dx = jr[3] - jr[0], dy = jr[4] - jr[1], dz = jr[5] - jr[2];
            float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= dl;
            dy /= dl;
            dz /= dl;
            float bx = 0F, by = -0.25F, bz = 1F;
            float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            bx /= bl;
            by /= bl;
            bz /= bl;
            // axis that turns the rest direction towards the back
            float cxp = dy * bz - dz * by, cyp = dz * bx - dx * bz, czp = dx * by - dy * bx;
            float cl = (float) Math.sqrt(cxp * cxp + cyp * cyp + czp * czp);
            float dot = Math.max(-1F, Math.min(1F, dx * bx + dy * by + dz * bz));
            float ang = (float) Math.acos(dot);
            if (cl < 1e-5F) {
                cxp = 0F;
                cyp = 1F;
                czp = 0F;
            } else {
                cxp /= cl;
                cyp /= cl;
                czp /= cl;
            }
            float[] rBase = new float[12];
            axisAngle(cxp, cyp, czp, ang * (1F - fan) * (emerge > 0F ? 1F : 0F), rBase);
            // two axes across the tentacle, for the writhing
            float ux = 0F, uy = -1F, uz = 0F;
            float a1x = dy * uz - dz * uy, a1y = dz * ux - dx * uz, a1z = dx * uy - dy * ux;
            float a1l = (float) Math.sqrt(a1x * a1x + a1y * a1y + a1z * a1z);
            if (a1l < 1e-4F) {
                a1x = 1F;
                a1y = 0F;
                a1z = 0F;
            } else {
                a1x /= a1l;
                a1y /= a1l;
                a1z /= a1l;
            }
            float a2x = dy * a1z - dz * a1y, a2y = dz * a1x - dx * a1z, a2z = dx * a1y - dy * a1x;
            float seed = ti * 1.93F;
            // G0 = torso * T(c0) * Rbase
            translation(c0x, c0y, c0z, t);
            mul(torso, t, a);
            mul(a, rBase, g);                              // g = global of joint 0
            float[] store = tentSkin[ti];
            // S0 = G0 * T(-c0)
            translation(-c0x, -c0y, -c0z, t);
            mul(g, t, a);
            System.arraycopy(a, 0, store, 0, 12);
            float px = c0x, py = c0y, pz = c0z;            // scaled rest position of the previous joint
            for (int j = 1; j < J; j++) {
                float rx = c0x + k * (jr[j * 3] - c0x), ry = c0y + k * (jr[j * 3 + 1] - c0y), rz = c0z + k * (jr[j * 3 + 2] - c0z);
                float amp = (0.65F + 0.08F * j) * e * 0.78F;
                float wave = (float) Math.sin(age * 0.065F - j * 0.55F + seed);
                float sway = (float) Math.sin(age * 0.050F - j * 0.48F + seed * 1.7F + 1.3F);
                axisAngle(a1x, a1y, a1z, 0.20F * amp * wave, b);
                axisAngle(a2x, a2y, a2z, 0.16F * amp * sway, c);
                mul(b, c, d);                              // joint rotation
                translation(rx - px, ry - py, rz - pz, t);
                mul(g, t, a);                              // G(j-1) * T(offset)
                mul(a, d, g);                              // * R(j)
                translation(-rx, -ry, -rz, t);
                mul(g, t, a);
                System.arraycopy(a, 0, store, j * 12, 12);
                px = rx;
                py = ry;
                pz = rz;
            }
            // skin the vertices: a small copy of the rest shape (scaled around the first joint) between two joints
            float[] vp = tent.pos[ti], vn = tent.nrm[ti], vs = tent.along[ti];
            for (int v = 0; v < tent.vertCount[ti]; v++) {
                float x = c0x + k * (vp[v * 3] - c0x), y = c0y + k * (vp[v * 3 + 1] - c0y), z = c0z + k * (vp[v * 3 + 2] - c0z);
                float nx = vn[v * 3], ny = vn[v * 3 + 1], nz = vn[v * 3 + 2];
                float f = vs[v] * (J - 1);
                int j0 = Math.min((int) f, J - 2);
                float w1 = f - j0;
                float w0 = 1F - w1;
                int o0 = j0 * 12, o1 = (j0 + 1) * 12;
                float ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
                for (int q = 0; q < 2; q++) {
                    float wq = q == 0 ? w0 : w1;
                    int o = q == 0 ? o0 : o1;
                    ox += wq * (store[o] * x + store[o + 1] * y + store[o + 2] * z + store[o + 9]);
                    oy += wq * (store[o + 3] * x + store[o + 4] * y + store[o + 5] * z + store[o + 10]);
                    oz += wq * (store[o + 6] * x + store[o + 7] * y + store[o + 8] * z + store[o + 11]);
                    onx += wq * (store[o] * nx + store[o + 1] * ny + store[o + 2] * nz);
                    ony += wq * (store[o + 3] * nx + store[o + 4] * ny + store[o + 5] * nz);
                    onz += wq * (store[o + 6] * nx + store[o + 7] * ny + store[o + 8] * nz);
                }
                float len = (float) Math.sqrt(onx * onx + ony * ony + onz * onz);
                if (len > 1e-6F) {
                    onx /= len;
                    ony /= len;
                    onz /= len;
                }
                outPos[ti][v * 3] = ox;
                outPos[ti][v * 3 + 1] = oy;
                outPos[ti][v * 3 + 2] = oz;
                outNrm[ti][v * 3] = onx;
                outNrm[ti][v * 3 + 1] = ony;
                outNrm[ti][v * 3 + 2] = onz;
            }
        }
    }
}
