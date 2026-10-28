package com.cnnceart.flymeears.protocol;

/**
 * 型号族内的 ANC 值映射。
 *
 * <p>标准映射来自 HyperEars：ANC = 0x02，关闭 = 0x01，通透 = 0x04。
 * Enco Air2 Pro 把 ANC / 关闭对调（0x01 / 0x02），因此单独保留一个
 * COMPATIBLE 配置；Enco X2 目前按标准映射处理，真机验证后再修正。
 */
public enum OppoWireConfig {
    STANDARD(0x02, 0x01),
    COMPATIBLE(0x01, 0x02);

    private final int ancPrimary;
    private final int offPrimary;

    OppoWireConfig(int ancPrimary, int offPrimary) {
        this.ancPrimary = ancPrimary;
        this.offPrimary = offPrimary;
    }

    public int ancPrimary() {
        return ancPrimary;
    }

    public int offPrimary() {
        return offPrimary;
    }

    public static final int TRANSPARENCY_PRIMARY = 0x04;

    /** 设备名是否属于 OPPO / Enco 家族（仅用于挑选候选，不作为能力证明）。 */
    public static boolean matchesName(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String normalized = deviceName.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("oppo") || normalized.contains("enco");
    }

    /** 按设备名挑选配置：目前只有 Air2 Pro 需要反向映射。 */
    public static OppoWireConfig forDeviceName(String deviceName) {
        String normalized = deviceName == null
                ? ""
                : deviceName.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("encoair2pro") ? COMPATIBLE : STANDARD;
    }
}
