package com.cnnceart.flymeears;

/** 模块内部广播契约：磁贴 / 详情页 ↔ SystemUI 会话。 */
public final class EncoIntents {

    /** 磁贴 / 详情页 → SystemUI：在当前模式间循环。 */
    public static final String ACTION_CYCLE = "com.cnnceart.flymeears.action.CYCLE_MODE";

    /** 详情页 → SystemUI：切到指定模式。 */
    public static final String ACTION_SET_MODE = "com.cnnceart.flymeears.action.SET_MODE";

    /** SystemUI → 磁贴 / 详情页：完整状态。 */
    public static final String ACTION_STATE = "com.cnnceart.flymeears.action.STATE";

    /** SystemUI → 磁贴：仅模式变化（保留旧契约）。 */
    public static final String ACTION_MODE = "com.cnnceart.flymeears.action.MODE_CHANGED";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_BATTERY = "battery";
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_CASE = "case";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_NAME = "name";

    private EncoIntents() {
    }
}
