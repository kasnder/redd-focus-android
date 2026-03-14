package net.kollnig.greasemilkyway;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Generates filter rule strings from AccessibilityNodeInfo nodes.
 * Picks the best available selector (viewId > contentDescription > text > className).
 */
public class ElementPickerRuleGenerator {
    private static final String TAG = "ElementPickerRuleGenerator";

    /**
     * Generate the best possible rule string for the given node and package.
     *
     * @param node        The selected accessibility node
     * @param packageName The package name of the foreground app
     * @param comment     User-provided comment (can be null)
     * @return A rule string in the app's filter format
     */
    public static String generateRule(AccessibilityNodeInfo node, String packageName, String comment) {
        StringBuilder rule = new StringBuilder(packageName);

        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        CharSequence className = node.getClassName();

        if (viewId != null && !viewId.isEmpty()) {
            rule.append("##viewId=").append(viewId);
        } else if (desc != null && desc.length() > 0) {
            rule.append("##desc=").append(desc.toString());
        } else if (text != null && text.length() > 0) {
            rule.append("##text=").append(text.toString());
        } else if (className != null && className.length() > 0) {
            rule.append("##className=").append(className.toString());
        }

        if (comment != null && !comment.isEmpty()) {
            rule.append("##comment=").append(comment);
        }

        return rule.toString();
    }

    /**
     * Generate a human-readable description of the node for displaying in the picker UI.
     */
    public static String describeNode(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();

        CharSequence className = node.getClassName();
        if (className != null) {
            // Show only the simple class name
            String fullName = className.toString();
            int lastDot = fullName.lastIndexOf('.');
            sb.append(lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName);
        }

        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) {
            // Show just the id part after the ':'
            int slashIndex = viewId.indexOf('/');
            String shortId = slashIndex >= 0 ? viewId.substring(slashIndex + 1) : viewId;
            sb.append(" #").append(shortId);
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(" \"").append(truncate(desc.toString(), 30)).append("\"");
        }

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(" [").append(truncate(text.toString(), 30)).append("]");
        }

        return sb.toString().trim();
    }

    /**
     * Get the best label for the confirmation dialog.
     */
    public static String getSelectorDescription(AccessibilityNodeInfo node) {
        String viewId = node.getViewIdResourceName();
        if (viewId != null && !viewId.isEmpty()) {
            return "View ID: " + viewId;
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return "Description: " + desc;
        }

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return "Text: " + truncate(text.toString(), 50);
        }

        CharSequence className = node.getClassName();
        if (className != null) {
            return "Class: " + className;
        }

        return "Unknown element";
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
