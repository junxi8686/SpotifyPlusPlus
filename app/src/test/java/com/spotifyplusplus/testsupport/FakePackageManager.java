package com.spotifyplusplus.testsupport;

/**
 * A {@link android.content.pm.PackageManager} stub for JVM tests.
 *
 * <p>{@code FakeAndroidContext} models a Spotify process: the companion app it publishes to
 * may or may not be installed. Before that was modelled, {@code getPackageManager()} fell
 * through to the stubbed platform class, which throws - and the bridge publisher reads a
 * failed probe as "the companion is absent", so no bind was ever attempted and the
 * publisher tests exited at {@code connect()} without exercising the provider/window path.
 *
 * <p>Only {@code getPackageInfo} answers; every other member is a generated stub that throws,
 * because the bridge asks nothing else of a package manager.
 */
public class FakePackageManager extends android.content.pm.PackageManager {
    /** The companion package the bridge publishes to. */
    public static final String COMPANION_PACKAGE = "com.eza.hyperglow";
    /** Packages this fake reports as installed. */
    public final java.util.Set<String> installed = new java.util.LinkedHashSet<>();

    public FakePackageManager() {
        installed.add(COMPANION_PACKAGE);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(String packageName, int flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        if (!installed.contains(packageName)) {
            throw new android.content.pm.PackageManager.NameNotFoundException(packageName);
        }
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = packageName;
        return info;
    }

    @Override
    public void addPackageToPreferred(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean addPermission(android.content.pm.PermissionInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean canRequestPackageInstalls() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int checkPermission(java.lang.String p0, java.lang.String p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int checkSignatures(int p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int checkSignatures(java.lang.String p0, java.lang.String p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void clearInstantAppCookie() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void clearPackagePreferredActivities(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void extendVerificationTimeout(int p0, int p1, long p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int getApplicationEnabledSetting(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ChangedPackages getChangedPackages(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int getComponentEnabledSetting(android.content.ComponentName p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String getInstallerPackageName(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public byte[] getInstantAppCookie() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String getNameForUid(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int[] getPackageGids(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int[] getPackageGids(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.PackageInstaller getPackageInstaller() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int getPackageUid(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String[] getPackagesForUid(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.String[] getSystemSharedLibraryNames() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean isInstantApp() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean isInstantApp(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public boolean isSafeMode() {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void removePackageFromPreferred(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void removePermission(java.lang.String p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void setApplicationCategoryHint(java.lang.String p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void updateInstantAppCookie(byte[] p0) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }

    @Override
    public void verifyPendingInstall(int p0, int p1) {
        throw new UnsupportedOperationException("FakePackageManager stub");
    }
}
