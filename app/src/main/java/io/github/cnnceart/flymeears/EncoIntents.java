package io.github.cnnceart.flymeears;

/** 模块内部广播契约：宿主详情页 ↔ SystemUI 会话。 */
public final class EncoIntents {

    /** 详情页 → SystemUI：在当前模式间循环。 */
    public static final String ACTION_CYCLE = "io.github.cnnceart.flymeears.action.CYCLE_MODE";

    /** 详情页 → SystemUI：切到指定模式。 */
    public static final String ACTION_SET_MODE = "io.github.cnnceart.flymeears.action.SET_MODE";

    /** 详情页「降噪方式」→ SystemUI：切到指定档位（魅族 17~20）。 */
    public static final String ACTION_SET_ANC_LEVEL = "io.github.cnnceart.flymeears.action.SET_ANC_LEVEL";

    /** 详情页功能开关（游戏模式 / 黄金听感 / 设备双连）。 */
    public static final String ACTION_SET_FEATURE = "io.github.cnnceart.flymeears.action.SET_FEATURE";

    /** 高品质音频编码。 */
    public static final String ACTION_SET_CODEC = "io.github.cnnceart.flymeears.action.SET_CODEC";

    /** SystemUI → 宿主：完整状态。 */
    public static final String ACTION_STATE = "io.github.cnnceart.flymeears.action.STATE";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ANC_LEVEL = "anc_level";
    public static final String EXTRA_FEATURE = "feature";
    public static final String EXTRA_ON = "on";
    public static final String EXTRA_CODEC = "codec";
    public static final String EXTRA_HI_RES = "hi_res";
    public static final String EXTRA_LOW_LATENCY = "low_latency";
    public static final String EXTRA_DUAL_CONNECTION = "dual_connection";
    public static final String EXTRA_PERSONAL_SOUND = "personal_sound";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_CASE = "case";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_NAME = "name";

    /** 详情页「音效」行 → SystemUI：设置 EQ 模式。 */
    public static final String ACTION_SET_EQ = "io.github.cnnceart.flymeears.action.SET_EQ";

    public static final String EXTRA_EQ = "eq";

    /** 宿主（详情页 / 设备中心卡片）→ SystemUI：断开这副耳机。 */
    public static final String ACTION_DISCONNECT = "io.github.cnnceart.flymeears.action.DISCONNECT";

    /** 「自定义 EQ」页 → SystemUI：下发 10 段增益曲线（0x0411 + 0x040E）。 */
    public static final String ACTION_SET_CURVE = "io.github.cnnceart.flymeears.action.SET_CURVE";
    /** 10 段增益，单位 dB，顺序同 EqualizerView 的频点。 */
    public static final String EXTRA_CURVE = "curve";

    /** 充电状态（bit7 语义，独立布尔值）。 */
    public static final String EXTRA_LEFT_CHARGING = "left_charging";
    public static final String EXTRA_RIGHT_CHARGING = "right_charging";
    public static final String EXTRA_BOX_CHARGING = "box_charging";

    /** 佩戴/在盒状态（0x0109：4=在盒、5=出盒未戴、7=佩戴）。 */
    public static final String EXTRA_WEAR_LEFT = "wear_left";
    public static final String EXTRA_WEAR_RIGHT = "wear_right";
    public static final String EXTRA_IN_BOX_LEFT = "inbox_left";
    public static final String EXTRA_IN_BOX_RIGHT = "inbox_right";

    /** 0x0112 多设备表：每项 "mac\u0001name"，当前设备排第一。 */
    public static final String EXTRA_DUAL_DEVICES = "dual_devices";

    /** 只要求回推当前状态，不要切换模式（详情页打开时用）。 */
    public static final String EXTRA_QUERY_ONLY = "query_only";

    private EncoIntents() {
    }
}