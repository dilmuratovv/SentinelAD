package uz.sentinel.ad.compat;

/** Decides whether the running Minecraft version is inside the supported range (1.21 to 26.x). */
public final class ServerCompatibility {
    private ServerCompatibility() {}

    /**
     * @param version a Minecraft version string such as {@code 1.21.4}, {@code 26.1.1} or {@code 1.21-rc1}
     * @return true for 1.21 and newer (including the year-based 26.x scheme)
     */
    public static boolean isSupported(String version) {
        if (version == null) return false;
        int[] parts = parse(version);
        if (parts[0] < 0) return false;
        if (parts[0] == 1) return parts[1] >= 21;
        return parts[0] >= 26;
    }

    /** Returns {major, minor}, or {-1, -1} when the string has no leading number. */
    static int[] parse(String version) {
        String[] pieces = version.trim().split("[.\\-]");
        int major = number(pieces, 0);
        int minor = number(pieces, 1);
        return major < 0 ? new int[]{-1, -1} : new int[]{major, Math.max(minor, 0)};
    }

    private static int number(String[] pieces, int index) {
        if (index >= pieces.length) return -1;
        try {
            return Integer.parseInt(pieces[index]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
