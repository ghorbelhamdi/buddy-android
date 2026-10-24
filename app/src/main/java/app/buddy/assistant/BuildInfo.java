package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.2.11";
    static final int VERSION_CODE = 179;

    private BuildInfo() {
    }
}
