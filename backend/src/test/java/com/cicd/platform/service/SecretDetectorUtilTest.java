package com.cicd.platform.service;

import com.cicd.platform.util.SecretDetectorUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SecretDetectorUtilTest {

    @Test
    public void testSecretFileDetection() {
        assertTrue(SecretDetectorUtil.isSecretFile(".env"));
        assertTrue(SecretDetectorUtil.isSecretFile("backend/.env"));
        assertTrue(SecretDetectorUtil.isSecretFile("config/github-private-key.pem"));
        assertTrue(SecretDetectorUtil.isSecretFile("server.key"));
        assertTrue(SecretDetectorUtil.isSecretFile("credentials.json"));
        assertTrue(SecretDetectorUtil.isSecretFile("id_rsa"));
    }

    @Test
    public void testNonSecretFiles() {
        assertFalse(SecretDetectorUtil.isSecretFile("pom.xml"));
        assertFalse(SecretDetectorUtil.isSecretFile("src/App.java"));
        assertFalse(SecretDetectorUtil.isSecretFile("README.md"));
    }

    @Test
    public void testContentSanitization() {
        String content = "SECRET_KEY=12345";
        String sanitized = SecretDetectorUtil.sanitizeContent(".env", content, false);
        assertEquals("[REDACTED: Sensitive file contents masked for security]", sanitized);

        String allowed = SecretDetectorUtil.sanitizeContent(".env", content, true);
        assertEquals(content, allowed);
    }
}
