package io.github.cnnceart.flymeears.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 自定义 EQ：10 段 → 6 段折叠与 0x040E 组帧。 */
public class EqCurveTest {

    @Test
    public void flatCurveStaysFlat() {
        int[] flat = new int[EqMapper.NATIVE_BANDS.length];
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, EqMapper.toOppoBands(flat));
    }

    @Test
    public void foldsTenBandsIntoSixByFrequencyRange() {
        // 只有 1kHz 抬 6dB，落在第 4 段（1000–2500Hz）。
        int[] nativeDb = new int[EqMapper.NATIVE_BANDS.length];
        nativeDb[5] = 6;                       // 1600Hz
        assertArrayEquals(new int[]{0, 0, 0, 6, 0, 0}, EqMapper.toOppoBands(nativeDb));

        // 62/100/200/400 同属低两段，取均值。
        int[] low = new int[EqMapper.NATIVE_BANDS.length];
        low[0] = 4;                            // 62Hz
        low[1] = 8;                            // 100Hz
        assertArrayEquals(new int[]{6, 0, 0, 0, 0, 0}, EqMapper.toOppoBands(low));
    }

    @Test
    public void snapsGainToDeviceSteps() {
        assertEquals(-55, OppoWireCodec.hearStep(-60));
        assertEquals(-5, OppoWireCodec.hearStep(-4));
        assertEquals(3, OppoWireCodec.hearStep(0));
        assertEquals(7, OppoWireCodec.hearStep(6));
        assertEquals(17, OppoWireCodec.hearStep(20));
    }

    @Test
    public void buildsProfileSelectAndTwelveBandCurve() {
        assertEquals("AA 0C 00 00 11 04 F0 05 00 01 01 01 00 12",
                OppoWireCodec.hex(OppoWireCodec.selectHearingProfile(0x12)));

        int[] left = {3, 3, 3, 3, 3, 3};
        int[] right = {3, 3, 3, 3, 3, 3};
        byte[] frame = OppoWireCodec.hearingCurve(left, right, 0x12, "x");
        assertEquals("AA 32 00 00 0E 04 F0 2B 00 03 0C "
                        + "01 01 03 01 02 03 01 03 03 01 04 03 01 05 03 01 06 03 "
                        + "02 01 03 02 02 03 02 03 03 02 04 03 02 05 03 02 06 03 "
                        + "00 00 00 12 78",
                OppoWireCodec.hex(frame));
        // 0x040E 之后紧跟 0x0403 0B 01 打开黄金听感。
        assertEquals("AA 09 00 00 03 04 F0 02 00 0B 01",
                OppoWireCodec.hex(OppoWireCodec.featureSwitch(OppoWireCodec.FEATURE_PERSONAL_SOUND, true)));
    }
}
