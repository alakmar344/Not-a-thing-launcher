package android.appwidget.flags;

/**
 * Compile-time stand-in for the framework's hidden appwidget aconfig flags. On devices whose
 * framework ships the real class, the boot class path version wins at runtime.
 */
public final class Flags {
    private Flags() {}

    public static boolean generatedPreviews() { return false; }
}
