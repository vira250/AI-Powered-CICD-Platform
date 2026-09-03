package com.cicd.platform.service;

import com.cicd.platform.util.FileTypeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FileTypeUtilTest {

    @Test
    public void testBinaryFileExtensions() {
        assertTrue(FileTypeUtil.isBinaryFile("assets/logo.png"));
        assertTrue(FileTypeUtil.isBinaryFile("image.jpg"));
        assertTrue(FileTypeUtil.isBinaryFile("archive.zip"));
        assertTrue(FileTypeUtil.isBinaryFile("target/app.jar"));
        assertTrue(FileTypeUtil.isBinaryFile("Application.class"));
        assertTrue(FileTypeUtil.isBinaryFile("binary.exe"));
        assertTrue(FileTypeUtil.isBinaryFile("document.pdf"));
    }

    @Test
    public void testTextFileExtensions() {
        assertFalse(FileTypeUtil.isBinaryFile("src/main/java/App.java"));
        assertFalse(FileTypeUtil.isBinaryFile("pom.xml"));
        assertFalse(FileTypeUtil.isBinaryFile("application.yml"));
        assertFalse(FileTypeUtil.isBinaryFile("README.md"));
        assertFalse(FileTypeUtil.isBinaryFile("Dockerfile"));
        assertFalse(FileTypeUtil.isBinaryFile(".gitignore"));
    }
}
