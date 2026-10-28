package io.github.cnnceart.flymeears;

/** 模块内部广播契约：磁贴 / 详情页 ↔ SystemUI 会话。 */
public final class EncoIntents {

    /** 磁贴 / 详情页 → SystemUI：在当前模式间循环。 */
    public static final String ACTION_CYCLE = "io.github.cnnceart.flymeears.action.CYCLE_MODE";

    /** 详情页 → SystemUI：切到指定模式。 */
    public static final String ACTION_SET_MODE = "io.github.cnnceart.flymeears.action.SET_MODE";

    /** SystemUI → 磁贴 / 详情页：完整状态。 */
    public static final String ACTION_STATE = "io.github.cnnceart.flymeears.action.STATE";

    /** SystemUI → 磁贴：仅模式变化（保留旧契约）。 */
    public static final String ACTION_MODE = "io.github.cnnceart.flymeears.action.MODE_CHANGED";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_BATTERY = "battery";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_CASE = "case";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_NAME = "name";

    /** 详情页「音效」行 → SystemUI：设置 EQ 模式。 */
    public static final String ACTION_SET_EQ = "io.github.cnnceart.flymeears.action.SET_EQ";

    public static final String EXTRA_EQ = "eq";

    /** 只要求回推当前状态，不要切换模式（详情页打开时用）。 */
    public static final String EXTRA_QUERY_ONLY = "query_only";

    /** 调试：向耳机发送一条任意命令（协议探测用）。 */
    public static final String ACTION_PROBE = "io.github.cnnceart.flymeears.action.PROBE";
    public static final String EXTRA_PROBE_CMD = "cmd";
    public static final String EXTRA_PROBE_PAYLOAD = "payload";

    private EncoIntents() {
    }
}
