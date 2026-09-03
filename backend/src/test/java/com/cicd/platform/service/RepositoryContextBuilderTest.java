package com.cicd.platform.service;

import com.cicd.platform.dto.*;
import com.cicd.platform.util.FileTypeUtil;
import com.cicd.platform.util.SecretDetectorUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RepositoryContext DTOs, FileTypeUtil, SecretDetectorUtil,
 * and the overall RepositoryContext data structure.
 * These tests verify the context assembly logic without requiring GitHub API access.
 */
public class RepositoryContextBuilderTest {

    @Test
    public void testCompleteRepositoryContextAssembly() {
        // Build a RepositoryContext manually to verify structure and serialisability
        RepositoryInfo repoInfo = new RepositoryInfo(123456L, "example", "spring-app", "main", "a83f91c987654321");

        List<RepositoryTreeNode> structure = List.of(
                new RepositoryTreeNode("src", "src", "directory"),
                new RepositoryTreeNode("src/main", "main", "directory"),
                new RepositoryTreeNode("src/main/java", "java", "directory"),
                new RepositoryTreeNode("src/main/java/App.java", "App.java", "text"),
                new RepositoryTreeNode("pom.xml", "pom.xml", "text"),
                new RepositoryTreeNode("assets/logo.png", "logo.png", "binary"),
                new RepositoryTreeNode(".env", ".env", "text")
        );

        List<RepositoryFileContent> files = List.of(
                new RepositoryFileContent("src/main/java/App.java", "App.java", "text", 1500L, "sha1",
                        "package com.example;\npublic class App { public static void main(String[] args){} }", false),
                new RepositoryFileContent("pom.xml", "pom.xml", "text", 2500L, "sha2",
                        "<project></project>", false),
                new RepositoryFileContent("assets/logo.png", "logo.png", "binary", 18234L, "sha3",
                        null, false),
                new RepositoryFileContent(".env", ".env", "text", 20L, "sha4",
                        SecretDetectorUtil.sanitizeContent(".env", "SECRET=12345", false), true)
        );

        RepositoryContext context = new RepositoryContext(repoInfo, structure, files);
        context.setTotalBytes(4020L);
        context.setTruncated(false);
        context.setMessage("Complete repository context successfully constructed.");

        // Verify repository metadata
        assertNotNull(context.getRepository());
        assertEquals("example", context.getRepository().getOwner());
        assertEquals("spring-app", context.getRepository().getRepositoryName());
        assertEquals("main", context.getRepository().getBranch());
        assertEquals("a83f91c987654321", context.getRepository().getCommitSha());
        assertEquals(123456L, context.getRepository().getGithubRepositoryId());

        // Verify complete structure includes all directories and files
        assertEquals(7, context.getStructure().size());
        assertTrue(context.getStructure().stream().anyMatch(n ->
                "directory".equals(n.getType()) && "src/main/java".equals(n.getPath())));
        assertTrue(context.getStructure().stream().anyMatch(n ->
                "text".equals(n.getType()) && "pom.xml".equals(n.getPath())));
        assertTrue(context.getStructure().stream().anyMatch(n ->
                "binary".equals(n.getType()) && "assets/logo.png".equals(n.getPath())));

        // Verify files list
        assertEquals(4, context.getFiles().size());

        // Binary file has null content (no UTF-8 corruption)
        var binaryFile = context.getFiles().stream()
                .filter(f -> "assets/logo.png".equals(f.getPath())).findFirst().orElseThrow();
        assertEquals("binary", binaryFile.getType());
        assertNull(binaryFile.getContent());
        assertEquals(18234L, binaryFile.getSize());

        // Text file contains actual source code
        var javaFile = context.getFiles().stream()
                .filter(f -> "src/main/java/App.java".equals(f.getPath())).findFirst().orElseThrow();
        assertEquals("text", javaFile.getType());
        assertTrue(javaFile.getContent().contains("class App"));
        assertFalse(javaFile.isSecret());

        // Secret file is detected and redacted
        var secretFile = context.getFiles().stream()
                .filter(f -> ".env".equals(f.getPath())).findFirst().orElseThrow();
        assertTrue(secretFile.isSecret());
        assertTrue(secretFile.getContent().contains("REDACTED"));

        // Payload metadata
        assertEquals(4020L, context.getTotalBytes());
        assertFalse(context.isTruncated());
    }

    @Test
    public void testNestedDirectoryStructure() {
        List<RepositoryTreeNode> structure = List.of(
                new RepositoryTreeNode("src", "src", "directory"),
                new RepositoryTreeNode("src/main", "main", "directory"),
                new RepositoryTreeNode("src/main/java", "java", "directory"),
                new RepositoryTreeNode("src/main/java/com", "com", "directory"),
                new RepositoryTreeNode("src/main/java/com/example", "example", "directory"),
                new RepositoryTreeNode("src/main/java/com/example/App.java", "App.java", "text")
        );

        // Verify deep nesting is preserved
        assertEquals(6, structure.size());
        assertEquals("src/main/java/com/example", structure.get(4).getPath());
        assertEquals("directory", structure.get(4).getType());
        assertEquals("src/main/java/com/example/App.java", structure.get(5).getPath());
        assertEquals("text", structure.get(5).getType());
    }

    @Test
    public void testBinaryFileClassification() {
        assertTrue(FileTypeUtil.isBinaryFile("assets/logo.png"));
        assertTrue(FileTypeUtil.isBinaryFile("lib/dependency.jar"));
        assertTrue(FileTypeUtil.isBinaryFile("dist/app.exe"));
        assertFalse(FileTypeUtil.isBinaryFile("src/App.java"));
        assertFalse(FileTypeUtil.isBinaryFile("Dockerfile"));
        assertFalse(FileTypeUtil.isBinaryFile(".gitignore"));
    }

    @Test
    public void testEmptyRepository() {
        RepositoryInfo info = new RepositoryInfo(1L, "owner", "empty-repo", "main", "abc123");
        RepositoryContext context = new RepositoryContext(info, List.of(), List.of());
        context.setTotalBytes(0L);
        context.setTruncated(false);

        assertNotNull(context.getRepository());
        assertEquals("abc123", context.getRepository().getCommitSha());
        assertTrue(context.getStructure().isEmpty());
        assertTrue(context.getFiles().isEmpty());
        assertEquals(0L, context.getTotalBytes());
        assertFalse(context.isTruncated());
    }

    @Test
    public void testPayloadSizeTruncation() {
        RepositoryContext context = new RepositoryContext();
        context.setTotalBytes(10_000_000L);
        context.setTruncated(true);
        context.setMessage("Payload size limit (5 MB) exceeded");

        assertTrue(context.isTruncated());
        assertTrue(context.getMessage().contains("exceeded"));
    }

    @Test
    public void testSecretDetectionAndSanitization() {
        assertTrue(SecretDetectorUtil.isSecretFile(".env"));
        assertTrue(SecretDetectorUtil.isSecretFile("config/server.key"));
        assertTrue(SecretDetectorUtil.isSecretFile("github-private-key.pem"));
        assertFalse(SecretDetectorUtil.isSecretFile("pom.xml"));

        String redacted = SecretDetectorUtil.sanitizeContent(".env", "DB_PASSWORD=secret", false);
        assertTrue(redacted.contains("REDACTED"));

        String allowed = SecretDetectorUtil.sanitizeContent(".env", "DB_PASSWORD=secret", true);
        assertEquals("DB_PASSWORD=secret", allowed);
    }
}
