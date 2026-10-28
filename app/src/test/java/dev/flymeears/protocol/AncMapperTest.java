package dev.flymeears.protocol;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AncMapperTest {

    @Test
    public void standardMappingRoundTripsAllThreeModes() {
        OppoWireConfig config = OppoWireConfig.STANDARD;
        for (int mode : new int[]{AncMapper.MODE_OFF, AncMapper.MODE_ANC, AncMapper.MODE_TRANSPARENCY}) {
            int primary = AncMapper.toPrimary(mode, config);
            assertEquals(mode, AncMapper.toFlymeMode(new OppoWireCodec.AncState(primary, null), config));
        }
    }

    @Test
    public void compatibleMappingReversesAncAndOff() {
        OppoWireConfig config = OppoWireConfig.COMPATIBLE;
        assertEquals(AncMapper.MODE_ANC,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x01, null), config));
        assertEquals(AncMapper.MODE_OFF,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x02, null), config));
        // 标准映射下 0x02 是降噪，反向配置下必须读成关闭。
        assertEquals(AncMapper.MODE_OFF,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x02, null), config));
    }

    @Test
    public void acceptsFirmwareSpecificEncodings() {
        OppoWireConfig config = OppoWireConfig.STANDARD;
        assertEquals(AncMapper.MODE_ANC,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x00, 0x08), config));
        assertEquals(AncMapper.MODE_TRANSPARENCY,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x00, 0x01), config));
        assertEquals(AncMapper.MODE_TRANSPARENCY,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x00, 0x02), config));
        assertEquals(AncMapper.MODE_OFF,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x08, null), config));
    }

    @Test
    public void treatsIntensityVariantsAsAnc() {
        for (int intensity : new int[]{0x10, 0x20, 0x40, 0x80}) {
            assertEquals(AncMapper.MODE_ANC,
                    AncMapper.toFlymeMode(new OppoWireCodec.AncState(intensity, null),
                            OppoWireConfig.STANDARD));
        }
    }

    @Test
    public void reportsUnknownForUnrecognizedValues() {
        assertEquals(AncMapper.MODE_UNKNOWN,
                AncMapper.toFlymeMode(new OppoWireCodec.AncState(0x99, null), OppoWireConfig.STANDARD));
        assertEquals(AncMapper.MODE_UNKNOWN, AncMapper.toFlymeMode(null, OppoWireConfig.STANDARD));
        assertEquals(-1, AncMapper.toPrimary(99, OppoWireConfig.STANDARD));
    }
}
