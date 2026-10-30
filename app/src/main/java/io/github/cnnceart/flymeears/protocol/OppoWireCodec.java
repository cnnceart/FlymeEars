package io.github.cnnceart.flymeears.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * OPPO Enco 私有协议编解码。
 *
 * <p>移植自 HyperEars 的 {@code OppoWireCodec}（GPL-3.0-only）。仅做线缆层编解码，
 * 型号语义由 {@link OppoWireConfig} 决定：Enco Air2 Pro 家族把 ANC 与关闭两个值
 * 对调，其余型号使用标准映射。
 *
 * <p>帧格式（RFCOMM，服务 UUID {@code 0000079a-d102-11e1-9b23-00025b00a5a5}）：
 * <pre>
 *   AA | bodyLen(1) | 00 00 | command(2, LE) | sequence(1) | payloadLen(2, LE) | payload
 * </pre>
 * bodyLen = 7 + payloadLen，因此整帧长度 = bodyLen + 2。
 */
public final class OppoWireCodec {

    public static final int QUERY_BATTERY = 0x0106;
    public static final int BATTERY_RESPONSE = 0x8106;
    public static final int QUERY_ANC = 0x010C;
    public static final int ANC_RESPONSE = 0x810C;
    public static final int SET_ANC = 0x0404;
    public static final int ACTIVE_REPORT = 0x0204;
    public static final int QUERY_NOTIFICATION_SUPPORT = 0x0200;
    public static final int NOTIFICATION_SUPPORT_RESPONSE = 0x8200;
    public static final int REGISTER_NOTIFICATIONS = 0x0205;

    /** 功能开关：payload = 功能位 + 开关值（欢律实测）。 */
    public static final int FEATURE_SWITCH = 0x0403;
    /** 高品质音频编码：payload = 编码类型 + 00 00。 */
    public static final int SET_CODEC = 0x041A;

    /** 黄金听感 / 自定义曲线：0x0411 选设备内档案，0x040E 写 12 段增益。 */
    public static final int SET_EAR_RESTORE = 0x0411;
    public static final int SET_HEARING_CURVE = 0x040E;
    /** 欢律「黄金听感」抓包里的 restoreDesId。 */
    public static final int HEARING_PROFILE_ID = 0x12;
    /** 设备可选增益档位（dB，实测 12 档，见 docs/protocol-oppo-enco.md）。 */
    private static final int[] HEARING_STEPS =
            {-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17};

    public static final int FEATURE_GAME_MODE = 0x06;
    public static final int FEATURE_PERSONAL_SOUND = 0x0B;
    public static final int FEATURE_DUAL_CONNECTION = 0x11;

    /** OPPO 编码枚举（0x041A 的取值）。 */
    public static final int CODEC_AAC = 0x02;
    public static final int CODEC_LDAC = 0x03;
    public static final int CODEC_LHDC = 0x08;

    /** 功能开关帧。 */
    public static byte[] featureSwitch(int feature, boolean on) {
        return packet(FEATURE_SWITCH, new byte[]{(byte) feature, (byte) (on ? 1 : 0)});
    }

    /** 高品质音频编码帧。 */
    public static byte[] setCodec(int codec) {
        return setCodec(codec, false);
    }

    /** 高品质音频编码帧；第二个字节是 Hi-Res 开关。 */
    public static byte[] setCodec(int codec, boolean hiRes) {
        return packet(SET_CODEC, new byte[]{(byte) codec, (byte) (hiRes ? 1 : 0), 0x00});
    }

    /**
     * 选中设备内保存的听力档案。
     *
     * <p>实测 {@code 01 01 01 00 12}：count=1、type=1、长度=1、档案号 0x12，
     * 设备回 {@code 0x8411 00}。欢律在写曲线前都会先发这一帧。
     */
    public static byte[] selectHearingProfile(int profileId) {
        require(profileId >= 0 && profileId <= 0xFF, "profile out of range");
        return packet(SET_EAR_RESTORE, new byte[]{0x01, 0x01, 0x01, 0x00, (byte) profileId});
    }

    /**
     * 写 12 段增益曲线（左 6 段 + 右 6 段）。
     *
     * <p>实测格式：{@code 03 0C} + 12 组 {@code (通道, 频段, 增益)} + 4 字节档案号（BE）
     * + 档案名（UTF-8，无长度前缀）。通道 1=左、2=右；增益是 8 位有符号 dB，
     * 只接受 {@link #hearStep} 里的 12 个档位。
     */
    public static byte[] hearingCurve(int[] leftDb, int[] rightDb, int profileId, String name) {
        require(leftDb.length == rightDb.length, "channel length mismatch");
        int bands = leftDb.length;
        byte[] label = (name == null ? "" : name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + bands * 6 + 4 + label.length];
        payload[0] = 0x03;                       // actionType = 保存并应用
        payload[1] = (byte) (bands * 2);         // 组数 = 2 通道 × 段数
        int offset = 2;
        for (int band = 0; band < bands; band++) {
            payload[offset++] = 0x01;            // 左声道
            payload[offset++] = (byte) (band + 1);
            payload[offset++] = (byte) hearStep(leftDb[band]);
        }
        for (int band = 0; band < bands; band++) {
            payload[offset++] = 0x02;            // 右声道
            payload[offset++] = (byte) (band + 1);
            payload[offset++] = (byte) hearStep(rightDb[band]);
        }
        payload[offset++] = (byte) ((profileId >>> 24) & 0xFF);
        payload[offset++] = (byte) ((profileId >>> 16) & 0xFF);
        payload[offset++] = (byte) ((profileId >>> 8) & 0xFF);
        payload[offset] = (byte) (profileId & 0xFF);
        System.arraycopy(label, 0, payload, offset + 1, label.length);
        return packet(SET_HEARING_CURVE, payload);
    }

    /** 把任意 dB 值吸附到设备支持的 12 个档位。 */
    public static int hearStep(int db) {
        int best = HEARING_STEPS[0];
        int bestDistance = Math.abs(db - best);
        for (int step : HEARING_STEPS) {
            int distance = Math.abs(db - step);
            if (distance < bestDistance) {
                best = step;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 连接后依次下发的读取命令。 */
    public static final byte[] QUERY_BATTERY_PACKET = packet(QUERY_BATTERY);
    public static final byte[] QUERY_NOTIFICATION_SUPPORT_PACKET = packet(QUERY_NOTIFICATION_SUPPORT);
    public static final byte[] QUERY_ANC_PACKET = packet(QUERY_ANC, new byte[]{0x01, 0x01});

    private static final int HEADER = 0xAA;
    private static final int DEFAULT_SEQUENCE = 0xF0;
    private static final int FIXED_BODY_SIZE = 7;
    private static final int PAYLOAD_OFFSET = 9;
    private static final int MAX_PAYLOAD_SIZE = 248;
    private static final int MAX_NOTIFICATION_COUNT = MAX_PAYLOAD_SIZE - 1;
    private static final int STATUS_SUCCESS = 0x00;
    private static final int REPORT_BATTERY = 0x01;
    private static final int REPORT_WEAR = 0x02;
    private static final int COMPONENT_LEFT = 0x01;
    private static final int COMPONENT_RIGHT = 0x02;
    private static final int COMPONENT_CASE = 0x03;
    private static final int BATTERY_PERCENT_MASK = 0x7F;
    private static final int BATTERY_CHARGING_MASK = 0x80;

    private OppoWireCodec() {
    }

    public record Frame(int command, int sequence, byte[] payload, byte[] bytes) {
    }

    public record BatteryReading(int percent, boolean charging) {
    }

    public record BatteryState(BatteryReading left, BatteryReading right, BatteryReading caseBattery) {
    }

    public record AncState(int primary, Integer secondary) {
    }

    /** 设置降噪模式。primary 语义见 {@link OppoWireConfig}。 */
    public static byte[] setAnc(int primary) {
        return setAnc(primary, null);
    }

    public static byte[] setAnc(int primary, Integer secondary) {
        require(primary >= 0 && primary <= 0xFF, "primary out of range");
        require(secondary == null || (secondary >= 0 && secondary <= 0xFF), "secondary out of range");
        byte[] payload = secondary == null
                ? new byte[]{0x01, 0x01, (byte) primary}
                : new byte[]{0x01, 0x01, (byte) primary, (byte) (int) secondary};
        return packet(SET_ANC, payload);
    }

    public static byte[] registerNotifications(byte[] ids) {
        require(ids.length <= MAX_NOTIFICATION_COUNT, "too many notification ids");
        byte[] payload = new byte[ids.length + 1];
        payload[0] = (byte) ids.length;
        System.arraycopy(ids, 0, payload, 1, ids.length);
        return packet(REGISTER_NOTIFICATIONS, payload);
    }

    public static byte[] parseNotificationSupport(Frame frame) {
        if (frame.command() != NOTIFICATION_SUPPORT_RESPONSE || frame.payload().length < 2) {
            return null;
        }
        byte[] payload = frame.payload();
        if ((payload[0] & 0xFF) != STATUS_SUCCESS) {
            return null;
        }
        int count = payload[1] & 0xFF;
        if (payload.length < 2 + count) {
            return null;
        }
        byte[] ids = new byte[count];
        System.arraycopy(payload, 2, ids, 0, count);
        return ids;
    }

    public static BatteryState parseBatteryState(Frame frame) {
        byte[] pairs;
        if (frame.command() == BATTERY_RESPONSE) {
            pairs = frame.payload();
        } else if (frame.command() == ACTIVE_REPORT) {
            byte[] payload = frame.payload();
            if (payload.length < 2 || (payload[0] & 0xFF) != REPORT_BATTERY) {
                return null;
            }
            int count = payload[1] & 0xFF;
            int availablePairs = (payload.length - 2) / 2;
            if (availablePairs < count) {
                return null;
            }
            pairs = new byte[count * 2];
            System.arraycopy(payload, 2, pairs, 0, pairs.length);
        } else {
            return null;
        }
        if (pairs.length < 2) {
            return null;
        }

        BatteryReading left = null;
        BatteryReading right = null;
        BatteryReading caseBattery = null;
        for (int offset = 0; offset < pairs.length - 1; offset += 2) {
            int raw = pairs[offset + 1] & 0xFF;
            BatteryReading reading = new BatteryReading(
                    raw & BATTERY_PERCENT_MASK,
                    (raw & BATTERY_CHARGING_MASK) != 0);
            if (reading.percent() < 0 || reading.percent() > 100) {
                continue;
            }
            switch (pairs[offset] & 0xFF) {
                case COMPONENT_LEFT -> left = reading;
                case COMPONENT_RIGHT -> right = reading;
                case COMPONENT_CASE -> caseBattery = reading;
                default -> {
                }
            }
        }
        if (left == null && right == null && caseBattery == null) {
            return null;
        }
        return new BatteryState(left, right, caseBattery);
    }

    public static AncState parseAncState(Frame frame) {
        if (frame.command() != ANC_RESPONSE && frame.command() != ACTIVE_REPORT) {
            return null;
        }
        byte[] payload = frame.payload();
        if (frame.command() == ACTIVE_REPORT && payload.length > 0) {
            int report = payload[0] & 0xFF;
            if (report == REPORT_BATTERY || report == REPORT_WEAR) {
                return null;
            }
        }
        for (int offset = 0; offset < payload.length - 2; offset++) {
            if ((payload[offset] & 0xFF) != 0x01 || (payload[offset + 1] & 0xFF) != 0x01) {
                continue;
            }
            Integer secondary = offset + 3 < payload.length ? payload[offset + 3] & 0xFF : null;
            return new AncState(payload[offset + 2] & 0xFF, secondary);
        }
        return null;
    }

    public static byte[] packet(int command) {
        return packet(command, new byte[0]);
    }

    public static byte[] packet(int command, byte[] payload) {
        return packet(command, DEFAULT_SEQUENCE, payload);
    }

    public static byte[] packet(int command, int sequence, byte[] payload) {
        require(command >= 0 && command <= 0xFFFF, "command out of range");
        require(sequence >= 0 && sequence <= 0xFF, "sequence out of range");
        require(payload.length <= MAX_PAYLOAD_SIZE, "payload too large");

        int bodyLength = FIXED_BODY_SIZE + payload.length;
        byte[] out = new byte[2 + bodyLength];
        out[0] = (byte) HEADER;
        out[1] = (byte) bodyLength;
        out[2] = 0;
        out[3] = 0;
        writeShortLe(out, command, 4);
        out[6] = (byte) sequence;
        writeShortLe(out, payload.length, 7);
        System.arraycopy(payload, 0, out, PAYLOAD_OFFSET, payload.length);
        return out;
    }

    /** 流式解码器：容忍前导噪声、分片与粘包。 */
    public static final class Decoder {
        private byte[] bytes;
        private int size;

        public Decoder() {
            this(256);
        }

        public Decoder(int initialCapacity) {
            bytes = new byte[Math.max(16, initialCapacity)];
        }

        public List<Frame> offer(byte[] chunk) {
            List<Frame> frames = new ArrayList<>();
            if (chunk.length == 0) {
                return frames;
            }
            append(chunk);
            while (true) {
                discardNoise();
                if (size < 2) {
                    return frames;
                }
                int bodyLength = peek(1);
                if (bodyLength < FIXED_BODY_SIZE) {
                    discard(1);
                    continue;
                }
                int frameLength = bodyLength + 2;
                if (size < frameLength) {
                    return frames;
                }
                byte[] candidate = take(frameLength);
                int payloadLength = readShortLe(candidate, 7);
                if (payloadLength != bodyLength - FIXED_BODY_SIZE) {
                    continue;
                }
                byte[] payload = new byte[payloadLength];
                System.arraycopy(candidate, PAYLOAD_OFFSET, payload, 0, payloadLength);
                frames.add(new Frame(readShortLe(candidate, 4), candidate[6] & 0xFF, payload, candidate));
            }
        }

        public void reset() {
            size = 0;
        }

        private void append(byte[] chunk) {
            ensureCapacity(size + chunk.length);
            System.arraycopy(chunk, 0, bytes, size, chunk.length);
            size += chunk.length;
        }

        private void discardNoise() {
            int count = 0;
            while (count < size && peek(count) != HEADER) {
                count++;
            }
            if (count > 0) {
                discard(count);
            }
        }

        private int peek(int index) {
            return bytes[index] & 0xFF;
        }

        private byte[] take(int count) {
            byte[] out = new byte[count];
            System.arraycopy(bytes, 0, out, 0, count);
            discard(count);
            return out;
        }

        private void discard(int count) {
            if (count >= size) {
                size = 0;
                return;
            }
            System.arraycopy(bytes, count, bytes, 0, size - count);
            size -= count;
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length) {
                return;
            }
            int capacity = bytes.length;
            while (capacity < required) {
                capacity *= 2;
            }
            byte[] grown = new byte[capacity];
            System.arraycopy(bytes, 0, grown, 0, size);
            bytes = grown;
        }
    }

    public static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(java.util.Locale.ROOT, "%02X", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private static void writeShortLe(byte[] target, int value, int offset) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static int readShortLe(byte[] source, int offset) {
        return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
