package io.github.cnnceart.flymeears.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 卡片名称改写的纯函数测试（不依赖 Android 运行时）。 */
public final class CardNameHooksTest {

    @Test
    public void replacesShortNameWithBluetoothFullName() {
        assertEquals("{\"name\":\"OPPO Enco X2\"}",
                CardNameHooks.withFullName("{\"name\":\"Enco X2\"}", "OPPO Enco X2"));
    }

    @Test
    public void keepsJsonWhenFullNameAlreadyPresent() {
        String json = "{\"name\":\"OPPO Enco X2\"}";
        assertEquals(json, CardNameHooks.withFullName(json, "OPPO Enco X2"));
    }

    @Test
    public void keepsJsonWhenShortNameMissing() {
        String json = "{\"name\":\"MEIZU TWS 2311\"}";
        assertEquals(json, CardNameHooks.withFullName(json, "OPPO Enco X2"));
    }

    @Test
    public void keepsJsonWhenFullNameHasNoBrandPrefix() {
        String json = "{\"name\":\"Enco X2\"}";
        assertEquals(json, CardNameHooks.withFullName(json, "Enco X2"));
    }

    @Test
    public void keepsNullAndEmptyInputs() {
        assertNull(CardNameHooks.withFullName(null, "OPPO Enco X2"));
        assertEquals("{}", CardNameHooks.withFullName("{}", ""));
        assertEquals("{}", CardNameHooks.withFullName("{}", null));
    }
}
