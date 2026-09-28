package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class AndroidQuickTilesTest {
    @Test
    public void onlyApplicationTilesAreListedInPanelOrder() {
        assertEquals(List.of(
                        "com.vpn.app/com.vpn.app.VpnTile",
                        "org.example/.Tile"),
                AndroidQuickTiles.parseCustomTiles(
                        "wifi,cell,custom(com.vpn.app/com.vpn.app.VpnTile),bt,"
                                + " custom(org.example/.Tile) ,flashlight"));
    }

    @Test
    public void unsetMalformedAndDuplicateEntriesAreIgnored() {
        assertEquals(List.of(), AndroidQuickTiles.parseCustomTiles(null));
        assertEquals(List.of(), AndroidQuickTiles.parseCustomTiles("null"));
        assertEquals(List.of("a/.B"), AndroidQuickTiles.parseCustomTiles(
                "custom(a/.B),custom(a/.B),custom(noslash),custom(/x),custom(a/),"
                        + "custom(a/b c),custom(x/.Y"));
    }
}
