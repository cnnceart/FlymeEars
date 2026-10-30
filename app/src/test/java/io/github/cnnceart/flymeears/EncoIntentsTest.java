package io.github.cnnceart.flymeears;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 广播参数白名单的纯函数测试。 */
public final class EncoIntentsTest {

    @Test
    public void modeAcceptsOnlyThreeFlymeStates() {
        assertTrue(EncoIntents.validMode(1));
        assertTrue(EncoIntents.validMode(2));
        assertTrue(EncoIntents.validMode(3));
        assertFalse(EncoIntents.validMode(0));
        assertFalse(EncoIntents.validMode(4));
        assertFalse(EncoIntents.validMode(-1));
    }

    @Test
    public void ancLevelAcceptsOnlyFourIntensities() {
        assertTrue(EncoIntents.validAncLevel(17));
        assertTrue(EncoIntents.validAncLevel(18));
        assertTrue(EncoIntents.validAncLevel(19));
        assertTrue(EncoIntents.validAncLevel(20));
        assertFalse(EncoIntents.validAncLevel(16));
        assertFalse(EncoIntents.validAncLevel(21));
        assertFalse(EncoIntents.validAncLevel(-1));
    }

    @Test
    public void featureAcceptsOnlyKnownSwitches() {
        assertTrue(EncoIntents.validFeature(0x06));
        assertTrue(EncoIntents.validFeature(0x0B));
        assertTrue(EncoIntents.validFeature(0x11));
        assertFalse(EncoIntents.validFeature(0x00));
        assertFalse(EncoIntents.validFeature(0x07));
        assertFalse(EncoIntents.validFeature(0x12));
    }

    @Test
    public void codecAcceptsOnlyKnownWireValues() {
        assertTrue(EncoIntents.validCodec(0x02));
        assertTrue(EncoIntents.validCodec(0x03));
        assertTrue(EncoIntents.validCodec(0x08));
        assertFalse(EncoIntents.validCodec(0x00));
        assertFalse(EncoIntents.validCodec(0x01));
        assertFalse(EncoIntents.validCodec(0x07));
        assertFalse(EncoIntents.validCodec(-1));
    }

    @Test
    public void eqAcceptsOnlyPresetRange() {
        assertTrue(EncoIntents.validEq(0));
        assertTrue(EncoIntents.validEq(3));
        assertFalse(EncoIntents.validEq(-1));
        assertFalse(EncoIntents.validEq(4));
    }

    @Test
    public void batteryAcceptsOnlyPercentRange() {
        assertTrue(EncoIntents.validBattery(0));
        assertTrue(EncoIntents.validBattery(100));
        assertFalse(EncoIntents.validBattery(-1));
        assertFalse(EncoIntents.validBattery(101));
    }
}
