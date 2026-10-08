package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "1.0.0";
    static final int VERSION_CODE = 56;

    private BuildInfo() {
    }
}
