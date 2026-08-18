package com.aicicd.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCicdApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(AiCicdApplication.class, args);
    }

    private static void loadDotEnv() {
        File[] envFiles = new File[]{new File(".env"), new File("../.env")};
        for (File f : envFiles) {
            if (f.exists() && f.isFile()) {
                try {
                    List<String> lines = Files.readAllLines(f.toPath());
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                            continue;
                        }
                        int idx = line.indexOf('=');
                        String key = line.substring(0, idx).trim();
                        String val = line.substring(idx + 1).trim();
                        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        } else if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        }
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, val);
                        }
                    }
                    System.out.println(">>> Loaded .env configuration from: " + f.getAbsolutePath());
                    break;
                } catch (Exception e) {
                    System.err.println("Failed to read .env from " + f.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
    }
}
