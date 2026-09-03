package com.cicd.platform.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class FileTypeUtil {

    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp", "avif", "tiff", "psd", "ai",
            "mp3", "mp4", "wav", "avi", "mov", "mkv", "webm", "flv", "aac", "ogg",
            "zip", "tar", "gz", "7z", "rar", "bz2", "xz",
            "exe", "dll", "so", "dylib", "bin", "dat", "db", "sqlite",
            "class", "jar", "war", "ear", "pyc", "o", "obj", "wasm",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "ttf", "otf", "woff", "woff2", "eot", "cur"
    ));

    /**
     * Determines whether a file path points to a binary file based on its extension.
     */
    public static boolean isBinaryFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String fileName = path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileName = path.substring(lastSlash + 1);
        }

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return false;
        }

        String ext = fileName.substring(lastDot + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }
}
