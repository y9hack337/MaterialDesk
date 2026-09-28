package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Process;

public final class MagicDeskApplication extends Application {
    private static Context sApplicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sApplicationContext = getApplicationContext();
        DesktopUiFactory.applySystemPalette(this);
        // Auxiliary Activity processes own only their UI. Runtime startup and
        // recovery belong to the process hosting our service and Binder provider.
        if (!isPrimaryProcess(getProcessName(), getApplicationInfo().processName)) {
            return;
        }
        AndroidActivityResultStore.releaseOrphanedPersistedUris(this);
        DesktopHomeStartupGuard.relinquishStaleHome(this);
        IntegrationPackage.active();
        ShellBackend.active();
        RuntimeLimits.active();
        TermuxConnectionStatus.initialize(this);
        ShellAccess.initialize();
        DesktopSetupStatus.initialize(this);
        CompatibilityDiagnostics.initialize(this);
        DesktopSystemTheme.initialize(this);
        PlatformDesktopRecovery.initialize();
        DesktopAutomationEventJournal.record(
                "process",
                "started",
                true,
                "pid=" + Process.myPid());
    }

    @Override
    public void onConfigurationChanged(final Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Wallpaper color changes arrive as resource configuration changes.
        DesktopUiFactory.applySystemPalette(this);
    }

    static boolean isPrimaryProcess(
            final String processName, final String applicationProcessName) {
        return processName != null && processName.equals(applicationProcessName);
    }

    public static Context applicationContext() {
        return sApplicationContext;
    }
}
