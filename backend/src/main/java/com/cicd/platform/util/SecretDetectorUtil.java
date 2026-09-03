package com.cicd.platform.util;

import java.util.Locale;

public class SecretDetectorUtil {

    /**
     * Checks if a file path matches known sensitive / secret file patterns.
     */
    public static boolean isSecretFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String lowerPath = path.toLowerCase(Locale.ROOT);
        String fileName = lowerPath;
        int lastSlash = Math.max(lowerPath.lastIndexOf('/'), lowerPath.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            fileName = lowerPath.substring(lastSlash + 1);
        }

        if (fileName.equals(".env") || fileName.startsWith(".env.")
                || fileName.endsWith(".pem") || fileName.endsWith(".key")
                || fileName.endsWith(".pkcs12") || fileName.endsWith(".p12")
                || fileName.startsWith("credentials.") || fileName.equals("credentials.json")
                || fileName.startsWith("secret") || fileName.startsWith("id_rsa")
                || fileName.startsWith("id_ecdsa") || fileName.startsWith("id_ed25519")) {
            return true;
        }

        return false;
    }

    /**
     * Sanitizes secret file content if secret protection is enabled.
     */
    public static String sanitizeContent(String path, String originalContent, boolean includeSecrets) {
        if (includeSecrets || originalContent == null) {
            return originalContent;
        }

        if (isSecretFile(path)) {
            return "[REDACTED: Sensitive file contents masked for security]";
        }

        return originalContent;
    }
}
