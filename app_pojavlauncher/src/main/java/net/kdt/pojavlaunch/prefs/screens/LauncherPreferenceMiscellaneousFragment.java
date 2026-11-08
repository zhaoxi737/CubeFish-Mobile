package net.kdt.pojavlaunch.prefs.screens;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.preference.Preference;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.GLInfoUtils;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_misc);
        Preference driverPreference = requirePreference("zinkPreferSystemDriver");
        PackageManager packageManager = driverPreference.getContext().getPackageManager();
        boolean supportsTurnip = Tools.checkVulkanSupport(packageManager) && GLInfoUtils.getGlInfo().isAdreno();
        driverPreference.setVisible(supportsTurnip);
        refreshMirrorSummary();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        refreshMirrorSummary();
    }

    /** 方块鱼：自定义镜像地址已填写时，直接把地址显示在设置项下面 */
    private void refreshMirrorSummary() {
        Preference mirrorPreference = findPreference("customMirrorUrl");
        if (mirrorPreference == null) return;
        String current = LauncherPreferences.PREF_CUSTOM_MIRROR_URL;
        if (current == null || current.trim().isEmpty()) {
            mirrorPreference.setSummary(R.string.preference_custom_mirror_description);
        } else {
            mirrorPreference.setSummary(current.trim());
        }
    }
}
