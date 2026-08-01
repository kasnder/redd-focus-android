package net.kollnig.greasemilkyway;

/** Small text-only operations for a persisted custom rule line. */
public final class RuleText {
    private RuleText() {
    }

    /**
     * Replaces a rule line's comment without parsing or rebuilding its other fragments.
     * Comments cannot contain the rule delimiter, just as any other rule value cannot.
     */
    public static String withComment(String ruleLine, String newComment) {
        if (ruleLine == null || newComment == null) {
            throw new IllegalArgumentException("Rule line and comment are required");
        }
        if (newComment.contains("##")) {
            throw new IllegalArgumentException("A rule name cannot contain ##");
        }

        String commentPrefix = "##comment=";
        int commentStart = ruleLine.indexOf(commentPrefix);
        if (commentStart < 0) {
            return ruleLine + commentPrefix + newComment;
        }

        int valueStart = commentStart + commentPrefix.length();
        int valueEnd = ruleLine.indexOf("##", valueStart);
        if (valueEnd < 0) {
            valueEnd = ruleLine.length();
        }
        return ruleLine.substring(0, valueStart) + newComment + ruleLine.substring(valueEnd);
    }
}
