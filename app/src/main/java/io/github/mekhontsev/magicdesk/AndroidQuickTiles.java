package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Application tiles from Android's own Quick Settings, such as a VPN toggle.
 *
 * <p>Only SystemUI may bind a tile service, so tiles are read from SystemUI's
 * tile list and pressed through {@code cmd statusbar click-tile}, both with
 * the shared privileged service. Built-in system tiles (Wi-Fi, flashlight)
 * have no component and cannot be pressed this way; the phone's real Quick
 * Settings panel remains available for them.
 */
final class AndroidQuickTiles {
    /** One application tile; {@code tintIcon} is false for full-color app icons. */
    record Tile(String component, CharSequence label, Drawable icon, boolean tintIcon) {
    }

    private static final String TILES_SETTING = "sysui_qs_tiles";
    private static final String CUSTOM_PREFIX = "custom(";

    private AndroidQuickTiles() {
    }

    /** Flattened components of the {@code custom(...)} entries, in panel order. */
    static List<String> parseCustomTiles(final String spec) {
        final Set<String> components = new LinkedHashSet<>();
        if (spec == null) {
            return List.of();
        }
        for (final String raw : spec.trim().split(",")) {
            final String entry = raw.trim();
            if (!entry.startsWith(CUSTOM_PREFIX) || !entry.endsWith(")")) {
                continue;
            }
            final String component = entry.substring(CUSTOM_PREFIX.length(), entry.length() - 1).trim();
            final int slash = component.indexOf('/');
            if (slash > 0 && slash < component.length() - 1 && component.indexOf(' ') < 0) {
                components.add(component);
            }
        }
        return List.copyOf(components);
    }

    /** Reads the current application tiles; call from a worker thread. */
    static List<Tile> load(final Context context) throws IOException {
        final String spec = ShellAccess.run(
                "/system/bin/settings get secure " + TILES_SETTING).trim();
        final PackageManager packages = context.getPackageManager();
        final List<Tile> tiles = new ArrayList<>();
        for (final String flattened : parseCustomTiles(spec)) {
            final ComponentName component = ComponentName.unflattenFromString(flattened);
            if (component == null) {
                continue;
            }
            final ServiceInfo service;
            try {
                service = packages.getServiceInfo(component, 0);
            } catch (PackageManager.NameNotFoundException uninstalled) {
                continue;
            }
            final boolean tileIcon = service.icon != 0;
            tiles.add(new Tile(flattened, service.loadLabel(packages),
                    tileIcon ? service.loadIcon(packages)
                            : service.applicationInfo.loadIcon(packages),
                    tileIcon));
        }
        return tiles;
    }

    /** Presses a tile exactly as its Quick Settings button would. */
    static void click(final String component) throws IOException {
        ShellAccess.run("/system/bin/cmd statusbar click-tile "
                + ShellCommandLine.quote(component));
    }

    /** Opens Android's own Quick Settings panel on the phone. */
    static void expandOnPhone() throws IOException {
        ShellAccess.run("/system/bin/cmd statusbar expand-settings");
    }

    static boolean sameComponents(final List<Tile> first, final List<Tile> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).component().equals(second.get(index).component())) {
                return false;
            }
        }
        return true;
    }
}
