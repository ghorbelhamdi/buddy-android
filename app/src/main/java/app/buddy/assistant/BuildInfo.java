package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.0.0";
    static final int VERSION_CODE = 70;

    private BuildInfo() {
    }
}
