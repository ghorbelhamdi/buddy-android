package app.buddy.assistant;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.2.19";
    static final int VERSION_CODE = 188;
    /** Buddy's Google Play listing (the play flavor, signed by Google). */
    static final String PLAY_URL = "https://play.google.com/store/apps/details?id=app.buddy.assistant";

    private BuildInfo() {
    }
}
