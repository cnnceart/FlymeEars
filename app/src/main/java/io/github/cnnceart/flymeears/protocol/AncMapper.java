package io.github.cnnceart.flymeears.protocol;

/**
 * OPPO 原始 ANC 值与 Flyme 三态语义之间的转换。
 *
 * <p>纯函数，方便单元测试；{@code EncoLink} 只负责 I/O。
 * Flyme 侧语义与音量面板图标一致：1 关闭、2 降噪、3 通透。
 */
public final class AncMapper {

    public static final int MODE_UNKNOWN = 0;
    public static final int MODE_OFF = 1;
    public static final int MODE_ANC = 2;
    public static final int MODE_TRANSPARENCY = 3;

    /** 带强度档位的 ANC 回报，仍归入降噪。 */
    private static final int[] ANC_INTENSITY_VALUES = {0x10, 0x20, 0x40, 0x80};

    /** 魅族原生「降噪方式」四档：智能 / 深度 / 均衡 / 轻度。 */
    public static final int LEVEL_SMART = 17;
    public static final int LEVEL_DEEP = 18;
    public static final int LEVEL_BALANCE = 19;
    public static final int LEVEL_LIGHT = 20;

    private AncMapper() {
    }

    /** 魅族档位 → OPPO 0x0404 的 primary；未知返回 -1。 */
    public static int levelToPrimary(int level) {
        return switch (level) {
            case LEVEL_SMART -> 0x80;
            case LEVEL_DEEP -> 0x40;
            case LEVEL_BALANCE -> 0x20;
            case LEVEL_LIGHT -> 0x10;
            default -> -1;
        };
    }

    /** OPPO primary → 魅族档位；不是档位值返回 -1。 */
    public static int primaryToLevel(int primary) {
        return switch (primary) {
            case 0x80 -> LEVEL_SMART;
            case 0x40 -> LEVEL_DEEP;
            case 0x20 -> LEVEL_BALANCE;
            case 0x10 -> LEVEL_LIGHT;
            default -> -1;
        };
    }

    /** 是否为四档之一的 primary（区别于 01/02/04 这类三态值）。 */
    public static boolean isLevelPrimary(int primary) {
        return primaryToLevel(primary) > 0;
    }

    /** 原始回报 → Flyme 模式；无法识别时返回 {@link #MODE_UNKNOWN}。 */
    public static int toFlymeMode(OppoWireCodec.AncState state, OppoWireConfig config) {
        if (state == null) {
            return MODE_UNKNOWN;
        }
        int primary = state.primary();
        Integer secondary = state.secondary();
        boolean bareOrZero = secondary == null || secondary == 0x00;

        if (primary == config.ancPrimary() && bareOrZero) {
            return MODE_ANC;
        }
        if (primary == config.offPrimary() && bareOrZero) {
            return MODE_OFF;
        }
        if (primary == OppoWireConfig.TRANSPARENCY_PRIMARY && bareOrZero) {
            return MODE_TRANSPARENCY;
        }
        // 部分固件把通透编码成 primary=0x00 + secondary=0x01/0x02。
        if (primary == 0x00 && secondary != null && (secondary == 0x01 || secondary == 0x02)) {
            return MODE_TRANSPARENCY;
        }
        // 部分固件把降噪编码成 primary=0x00 + secondary=0x08。
        if (primary == 0x00 && secondary != null && secondary == 0x08) {
            return MODE_ANC;
        }
        // 少数型号用 0x08 表示关闭。
        if (primary == 0x08 && bareOrZero) {
            return MODE_OFF;
        }
        for (int intensity : ANC_INTENSITY_VALUES) {
            if (primary == intensity && bareOrZero) {
                return MODE_ANC;
            }
        }
        return MODE_UNKNOWN;
    }

    /** Flyme 模式 → 需要写入的 primary 值；未知模式返回 -1。 */
    public static int toPrimary(int flymeMode, OppoWireConfig config) {
        return switch (flymeMode) {
            case MODE_OFF -> config.offPrimary();
            case MODE_ANC -> config.ancPrimary();
            case MODE_TRANSPARENCY -> OppoWireConfig.TRANSPARENCY_PRIMARY;
            default -> -1;
        };
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case MODE_OFF -> "关闭";
            case MODE_ANC -> "降噪";
            case MODE_TRANSPARENCY -> "通透";
            default -> "未知";
        };
    }
}
