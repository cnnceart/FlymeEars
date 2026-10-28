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
