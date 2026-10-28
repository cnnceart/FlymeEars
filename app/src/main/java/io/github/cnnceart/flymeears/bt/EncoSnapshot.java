package io.github.cnnceart.flymeears.bt;

import java.util.Locale;

import io.github.cnnceart.flymeears.protocol.AncMapper;

/** 一次耳机会话的可观察状态。Flyme 侧只依赖这里的数据，不直接碰协议。 */
public final class EncoSnapshot {

    /** 与 Flyme 音量面板图标语义一致：1 = 关闭，2 = 降噪，3 = 通透。 */
    public static final int MODE_OFF = AncMapper.MODE_OFF;
    public static final int MODE_ANC = AncMapper.MODE_ANC;
    public static final int MODE_TRANSPARENCY = AncMapper.MODE_TRANSPARENCY;

    public final String address;
    public final String name;
    public final boolean connected;
    public final boolean protocolReady;
    public final int mode;
    /** OPPO EQ 模式（0..3），-1 表示还没读到。 */
    public final int eqMode;
    /** 0x0109 组件状态：4=在盒、5=出盒未戴、7=佩戴中；-1 未知。 */
    public final int leftStatus;
    public final int rightStatus;
    public final int leftBattery;
    public final int rightBattery;
    public final int caseBattery;
    public final boolean leftCharging;
    public final boolean rightCharging;
    public final boolean caseCharging;
    public final String lastError;

    private EncoSnapshot(Builder builder) {
        address = builder.address;
        name = builder.name;
        connected = builder.connected;
        protocolReady = builder.protocolReady;
        mode = builder.mode;
        eqMode = builder.eqMode;
        leftStatus = builder.leftStatus;
        rightStatus = builder.rightStatus;
        leftBattery = builder.leftBattery;
        rightBattery = builder.rightBattery;
        caseBattery = builder.caseBattery;
        leftCharging = builder.leftCharging;
        rightCharging = builder.rightCharging;
        caseCharging = builder.caseCharging;
        lastError = builder.lastError;
    }

    public static Builder builder(EncoSnapshot source) {
        return new Builder(source);
    }

    public static Builder builder(String address, String name) {
        Builder builder = new Builder(null);
        builder.address = address;
        builder.name = name;
        return builder;
    }

    public boolean hasBattery() {
        return leftBattery >= 0 || rightBattery >= 0 || caseBattery >= 0;
    }

    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(connected ? "已连接" : "未连接");
        if (protocolReady) {
            text.append(" · 协议就绪");
        }
        text.append(" · ").append(modeName(mode));
        if (leftBattery >= 0) {
            text.append(" · 左 ").append(leftBattery).append('%').append(leftCharging ? "+" : "");
        }
        if (rightBattery >= 0) {
            text.append(" · 右 ").append(rightBattery).append('%').append(rightCharging ? "+" : "");
        }
        if (caseBattery >= 0) {
            text.append(" · 盒 ").append(caseBattery).append('%').append(caseCharging ? "+" : "");
        }
        if (lastError != null) {
            text.append(" · ").append(lastError);
        }
        return text.toString();
    }

    public static String modeName(int mode) {
        return AncMapper.modeName(mode);
    }

    public static final class Builder {
        String address;
        String name;
        boolean connected;
        boolean protocolReady;
        int mode;
        int eqMode = -1;
        int leftStatus = -1;
        int rightStatus = -1;
        int leftBattery = -1;
        int rightBattery = -1;
        int caseBattery = -1;
        boolean leftCharging;
        boolean rightCharging;
        boolean caseCharging;
        String lastError;

        private Builder(EncoSnapshot source) {
            if (source != null) {
                address = source.address;
                name = source.name;
                connected = source.connected;
                protocolReady = source.protocolReady;
                mode = source.mode;
                eqMode = source.eqMode;
                leftStatus = source.leftStatus;
                rightStatus = source.rightStatus;
                leftBattery = source.leftBattery;
                rightBattery = source.rightBattery;
                caseBattery = source.caseBattery;
                leftCharging = source.leftCharging;
                rightCharging = source.rightCharging;
                caseCharging = source.caseCharging;
                lastError = source.lastError;
            }
        }

        public Builder connected(boolean value) {
            connected = value;
            return this;
        }

        public Builder protocolReady(boolean value) {
            protocolReady = value;
            return this;
        }

        public Builder mode(int value) {
            mode = value;
            return this;
        }

        public Builder eq(int value) {
            eqMode = value;
            return this;
        }

        public Builder leftStatus(int value) {
            leftStatus = value;
            return this;
        }

        public Builder rightStatus(int value) {
            rightStatus = value;
            return this;
        }

        public Builder left(int percent, boolean charging) {
            leftBattery = percent;
            leftCharging = charging;
            return this;
        }

        public Builder right(int percent, boolean charging) {
            rightBattery = percent;
            rightCharging = charging;
            return this;
        }

        public Builder caseBattery(int percent, boolean charging) {
            caseBattery = percent;
            caseCharging = charging;
            return this;
        }

        public Builder error(String value) {
            lastError = value;
            return this;
        }

        public EncoSnapshot build() {
            return new EncoSnapshot(this);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Builder(%s)", address);
        }
    }
}
