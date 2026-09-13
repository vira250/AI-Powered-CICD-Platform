package com.cicd.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubServiceTest {

    @Test
    void decodesPlainTextJobLogs() throws Exception {
        GitHubService service = new GitHubService(
                WebClient.builder(), new ObjectMapper(), "https://api.github.com", new GitHubAppService()
        );

        assertEquals("error TS2322\n", decode(service, "error TS2322\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void retainsZipJobLogCompatibility() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("job.txt"));
            zip.write("error TS2322\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        GitHubService service = new GitHubService(
                WebClient.builder(), new ObjectMapper(), "https://api.github.com", new GitHubAppService()
        );

        assertEquals("error TS2322\n\n", decode(service, output.toByteArray()));
    }

    @Test
    void mapsJsonNullConclusionToEmptyStringInsteadOfLiteralNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("", GitHubService.jsonText(mapper.readTree("{\"conclusion\":null}").path("conclusion")));
        assertEquals("failure", GitHubService.jsonText(mapper.readTree("{\"conclusion\":\"failure\"}").path("conclusion")));
        assertEquals("", GitHubService.jsonText(mapper.readTree("{}").path("conclusion")));
    }

    private String decode(GitHubService service, byte[] payload) throws Exception {
        Method method = GitHubService.class.getDeclaredMethod("decodeJobLogs", byte[].class);
        method.setAccessible(true);
        return (String) method.invoke(service, (Object) payload);
    }
}