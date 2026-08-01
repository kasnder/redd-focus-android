package net.kollnig.greasemilkyway;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/** App labels/icons have one fallback path so overview and detail cannot disagree. */
final class AppCatalog {
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> ICONS = new HashMap<>();
    static {
        NAMES.put("com.whatsapp", "WhatsApp"); ICONS.put("com.whatsapp", R.drawable.ic_whatsapp);
        NAMES.put("com.google.android.youtube", "YouTube"); ICONS.put("com.google.android.youtube", R.drawable.ic_youtube);
        NAMES.put("com.instagram.android", "Instagram"); ICONS.put("com.instagram.android", R.drawable.ic_instagram);
        NAMES.put("com.linkedin.android", "LinkedIn"); ICONS.put("com.linkedin.android", R.drawable.ic_linkedin);
    }
    private AppCatalog() { }
    static String getDisplayName(Context context, String packageName) {
        String known = NAMES.get(packageName);
        if (known != null) return known;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) { return packageName; }
    }
    static Drawable getIcon(Context context, String packageName) {
        Integer known = ICONS.get(packageName);
        if (known != null) return context.getDrawable(known);
        try { return context.getPackageManager().getApplicationIcon(packageName); }
        catch (PackageManager.NameNotFoundException ignored) { return context.getDrawable(android.R.drawable.sym_def_app_icon); }
    }
    static boolean isInstalled(Context context, String packageName) {
        try { context.getPackageManager().getApplicationInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }
}
