package io.github.mekhontsev.magicdesk;

/** Grid width is local to the Start viewport; the grid scrolls vertically. */
final class StartMenuLayout {
    /** Drawer cell width; six columns fit the desktop drawer as in Android's. */
    private static final int CELL_WIDTH_DP = 84;

    private StartMenuLayout() { }

    static int columns(final int widthDp) {
        return Math.max(1, widthDp / CELL_WIDTH_DP);
    }
}
