package io.github.cnnceart.flymeears.protocol;

/**
 * 魅族 10 段自定义 EQ ↔ OPPO 6 段听力补偿曲线。
 *
 * <p>魅族原生页的频点是固定的 10 个（{@link #NATIVE_BANDS}），OPPO 的
 * {@code 0x040E} 只有 6 段/声道（欢律 UI 命名为低频 … 极高频）。这里按对数频段
 * 把 10 段折叠成 6 段：落在同一区间的原生频点取平均，区间内没有频点时取最近的
 * 原生频点，保证任何一条曲线都能转成合法的 12 组增益。
 */
public final class EqMapper {

    /** 原生 EqualizerView 的 10 个频点（Hz）。 */
    public static final int[] NATIVE_BANDS = {62, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 16000};

    /** OPPO 6 段的区间上界（Hz）：低频 / 中低频 / 中频 / 中高频 / 高频 / 极高频。 */
    private static final int[] OPPO_EDGES = {150, 400, 1000, 2500, 6000, Integer.MAX_VALUE};

    private EqMapper() {
    }

    /** 10 段增益 → 6 段（每段取区间内原生频点的均值）。 */
    public static int[] toOppoBands(int[] nativeDb) {
        int[] out = new int[OPPO_EDGES.length];
        for (int band = 0; band < OPPO_EDGES.length; band++) {
            int lower = band == 0 ? 0 : OPPO_EDGES[band - 1];
            int sum = 0;
            int count = 0;
            for (int i = 0; i < NATIVE_BANDS.length && i < nativeDb.length; i++) {
                int freq = NATIVE_BANDS[i];
                if (freq >= lower && freq < OPPO_EDGES[band]) {
                    sum += nativeDb[i];
                    count++;
                }
            }
            out[band] = count > 0 ? Math.round(sum / (float) count) : nearest(nativeDb, lower);
        }
        return out;
    }

    /** 6 段 → 10 段：按频段归属复制，用于把设备侧状态回填到原生滑杆。 */
    public static int[] toNativeBands(int[] oppoDb) {
        int[] out = new int[NATIVE_BANDS.length];
        for (int i = 0; i < NATIVE_BANDS.length; i++) {
            int band = 0;
            while (band < OPPO_EDGES.length - 1 && NATIVE_BANDS[i] >= OPPO_EDGES[band]) {
                band++;
            }
            out[i] = band < oppoDb.length ? oppoDb[band] : 0;
        }
        return out;
    }

    private static int nearest(int[] nativeDb, int lower) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < NATIVE_BANDS.length && i < nativeDb.length; i++) {
            int distance = Math.abs(NATIVE_BANDS[i] - lower);
            if (distance < bestDistance) {
                best = nativeDb[i];
                bestDistance = distance;
            }
        }
        return best;
    }
}
