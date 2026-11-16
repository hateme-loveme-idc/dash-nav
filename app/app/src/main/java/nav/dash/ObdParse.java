package nav.dash;

/** ELM327 reply parsing (plain Java, host-testable). */
final class ObdParse {
    private ObdParse() {}

    /** RPM from an ELM327 reply to 010C ("41 0C 1A F8", with or without spaces/echo/headers), else -1. */
    static int rpm(String reply) {
        String hex = reply.toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-F\\n\\r]", "");
        for (String line : hex.split("[\\r\\n]+")) {
            int i = line.indexOf("410C");
            if (i >= 0 && line.length() >= i + 8) {
                try { return Integer.parseInt(line.substring(i + 4, i + 8), 16) / 4; } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

}
