package dev.flymeenco;

/** 模块内部广播契约：磁贴 ↔ SystemUI 会话。 */
public final class EncoIntents {

    /** 磁贴 → SystemUI：在当前模式间循环。 */
    public static final String ACTION_CYCLE = "dev.flymeenco.action.CYCLE_MODE";

    /** SystemUI → 磁贴：模式变化，extra {@link #EXTRA_MODE}。 */
    public static final String ACTION_MODE = "dev.flymeenco.action.MODE_CHANGED";

    public static final String EXTRA_MODE = "mode";

    /** 附加电量（左耳或整机电量，-1 表示未知）。 */
    public static final String EXTRA_BATTERY = "battery";

    private EncoIntents() {
    }
}
