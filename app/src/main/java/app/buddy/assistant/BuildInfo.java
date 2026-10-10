package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "1.1.0-spike";
    static final int VERSION_CODE = 69;

    private BuildInfo() {
    }
}
