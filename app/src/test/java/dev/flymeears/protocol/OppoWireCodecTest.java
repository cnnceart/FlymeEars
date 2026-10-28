package dev.flymeears.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 与 HyperEars 的 OppoWireCodecTest 保持同一组参考帧，确保移植等价。 */
public class OppoWireCodecTest {

    @Test
    public void buildsReferenceReadAndWritePackets() {
        assertEquals("AA 07 00 00 06 01 F0 00 00", OppoWireCodec.hex(OppoWireCodec.QUERY_BATTERY_PACKET));
        assertEquals("AA 09 00 00 0C 01 F0 02 00 01 01", OppoWireCodec.hex(OppoWireCodec.QUERY_ANC_PACKET));
        assertEquals("AA 07 00 00 00 02 F0 00 00",
                OppoWireCodec.hex(OppoWireCodec.QUERY_NOTIFICATION_SUPPORT_PACKET));
        assertEquals("AA 0A 00 00 04 04 F0 03 00 01 01 02", OppoWireCodec.hex(OppoWireCodec.setAnc(0x02)));
        assertEquals("AA 0B 00 00 04 04 F0 04 00 01 01 00 08",
                OppoWireCodec.hex(OppoWireCodec.setAnc(0x00, 0x08)));
    }

    @Test
    public void parsesAndRegistersAdvertisedNotificationIds() {
        OppoWireCodec.Frame response = decode(OppoWireCodec.packet(
                OppoWireCodec.NOTIFICATION_SUPPORT_RESPONSE, hex("00 04 01 02 03 F1")));
        byte[] ids = OppoWireCodec.parseNotificationSupport(response);

        assertNotNull(ids);
        assertEquals("01 02 03 F1", OppoWireCodec.hex(ids));
        assertEquals("AA 0C 00 00 05 02 F0 05 00 04 01 02 03 F1",
                OppoWireCodec.hex(OppoWireCodec.registerNotifications(ids)));
    }

    @Test
    public void decoderHandlesFragmentationNoiseAndConcatenatedFrames() {
        OppoWireCodec.Decoder decoder = new OppoWireCodec.Decoder();
        byte[] battery = OppoWireCodec.packet(OppoWireCodec.BATTERY_RESPONSE, hex("01 4B 02 CA 03 64"));
        byte[] anc = OppoWireCodec.packet(OppoWireCodec.ANC_RESPONSE, hex("01 01 04 00"));

        byte[] noisyHead = new byte[5];
        noisyHead[0] = 0x55;
        System.arraycopy(battery, 0, noisyHead, 1, 4);
        assertEquals(0, decoder.offer(noisyHead).size());

        byte[] rest = new byte[battery.length - 4 + anc.length];
        System.arraycopy(battery, 4, rest, 0, battery.length - 4);
        System.arraycopy(anc, 0, rest, battery.length - 4, anc.length);

        var frames = decoder.offer(rest);
        assertEquals(2, frames.size());
        assertEquals(OppoWireCodec.BATTERY_RESPONSE, frames.get(0).command());
        assertEquals(OppoWireCodec.ANC_RESPONSE, frames.get(1).command());
        assertEquals("01 01 04 00", OppoWireCodec.hex(frames.get(1).payload()));
    }

    @Test
    public void parsesBatteryComponentsAndChargingBit() {
        OppoWireCodec.Frame frame = decode(OppoWireCodec.packet(
                OppoWireCodec.BATTERY_RESPONSE, hex("01 4B 02 CA 03 64")));

        OppoWireCodec.BatteryState state = OppoWireCodec.parseBatteryState(frame);
        assertNotNull(state);
        assertEquals(new OppoWireCodec.BatteryReading(75, false), state.left());
        assertEquals(new OppoWireCodec.BatteryReading(74, true), state.right());
        assertEquals(new OppoWireCodec.BatteryReading(100, false), state.caseBattery());
    }

    @Test
    public void parsesActiveBatteryReportsWithoutTreatingThemAsAnc() {
        OppoWireCodec.Frame frame = decode(OppoWireCodec.packet(
                OppoWireCodec.ACTIVE_REPORT, hex("01 02 01 5A 02 52")));

        OppoWireCodec.BatteryState state = OppoWireCodec.parseBatteryState(frame);
        assertNotNull(state);
        assertEquals(new OppoWireCodec.BatteryReading(90, false), state.left());
        assertEquals(new OppoWireCodec.BatteryReading(82, false), state.right());
        assertNull(state.caseBattery());
        assertNull(OppoWireCodec.parseAncState(frame));
    }

    @Test
    public void parsesAncResponseAndIgnoresWearReport() {
        assertEquals(new OppoWireCodec.AncState(0x00, 0x08),
                OppoWireCodec.parseAncState(decode(OppoWireCodec.packet(
                        OppoWireCodec.ANC_RESPONSE, hex("00 01 01 00 08")))));
        assertNull(OppoWireCodec.parseAncState(decode(OppoWireCodec.packet(
                OppoWireCodec.ACTIVE_REPORT, hex("02 01 01 01")))));
    }

    @Test
    public void wireConfigSelectsAir2ProReverseMapping() {
        assertEquals(OppoWireConfig.STANDARD, OppoWireConfig.forDeviceName("OPPO Enco X2"));
        assertEquals(OppoWireConfig.STANDARD, OppoWireConfig.forDeviceName("OPPO Enco X3"));
        assertEquals(OppoWireConfig.COMPATIBLE, OppoWireConfig.forDeviceName("OPPO Enco Air2 Pro"));
        assertArrayEquals(new byte[]{(byte) 0xAA, 0x0A, 0, 0, 0x04, 0x04, (byte) 0xF0, 0x03, 0, 1, 1, 2},
                OppoWireCodec.setAnc(OppoWireConfig.STANDARD.ancPrimary()));
    }

    private static OppoWireCodec.Frame decode(byte[] bytes) {
        var frames = new OppoWireCodec.Decoder().offer(bytes);
        assertEquals(1, frames.size());
        return frames.get(0);
    }

    private static byte[] hex(String value) {
        String compact = value.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
