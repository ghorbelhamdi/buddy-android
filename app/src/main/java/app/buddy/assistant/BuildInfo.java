package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.3.9";
    static final int VERSION_CODE = 207;

    private BuildInfo() {
    }
}
