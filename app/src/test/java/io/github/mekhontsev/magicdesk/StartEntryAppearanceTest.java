package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StartEntryAppearanceTest {
    @Test public void borderIndicatesOnlySelectionOrKeyboardFocus() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android {
                    static class R { static class attr {
                        static final int state_selected = 1, state_focused = 2, state_pressed = 3,
                                state_hovered = 4;
                    } }
                }
                static class Color { static final int TRANSPARENT = 0; }
                record Background(int fill, int radius, int border) { }
                static class DesktopUiFactory {
                    static final int COLOR_SECONDARY_CONTAINER = 10, COLOR_TEXT = 11, COLOR_ACCENT = 12;
                    static int withAlpha(int color, float alpha) {
                        return color * 100 + Math.round(alpha * 100);
                    }
                    Background rounded(int fill, int radius, int border) {
                        return new Background(fill, radius, border);
                    }
                }
                static class StateListDrawable {
                    final Map<Integer, Background> states = new LinkedHashMap<>();
                    void addState(int[] state, Background background) {
                        states.put(state.length == 0 ? 0 : state[0], background);
                    }
                }
                final DesktopUiFactory mUi = new DesktopUiFactory();
                int dp(int value) { return value * 2; }
                public static void verify() {
                    for (int radius : new int[] {7, 12}) {
                        var states = new Fixture().entryBackground(radius).states;
                        check(states.size() == 5, "missing interactive state");
                        for (int state : new int[] {1, 2}) {
                            check(states.get(state).border() == DesktopUiFactory.COLOR_ACCENT,
                                    "selection or focus lost its outline");
                        }
                        check(states.get(0).fill() == Color.TRANSPARENT,
                                "ordinary entry draws a tile box");
                        for (int state : new int[] {0, 3, 4}) {
                            check(states.get(state).border() == states.get(state).fill(),
                                    "ordinary or pressed entry has a permanent outline");
                        }
                        check(states.values().stream().allMatch(value -> value.radius() == radius * 2),
                                "interactive state changes geometry");
                    }
                }
                """ + RuntimeSourceFixture.methods("StartMenuContent", "entryBackground"));
    }

    @Test public void gridAndSearchShareAppearanceIndependentOfLaunchBackend() throws Exception {
        final String tile = RuntimeSourceFixture.methods("StartMenuContent", "createAppTile");
        assertTrue(tile.contains("tile.setBackground(entryBackground(12))"));
        assertFalse(tile.contains("canFloat"));
        assertFalse(tile.contains("COLOR_ACCENT"));
        final String row = RuntimeSourceFixture.methods("StartMenuContent", "createSearchRow");
        assertTrue(row.contains("row.setBackground(entryBackground(7))"));
        assertTrue(row.contains("row.setSelected(selected)"));
    }
}
