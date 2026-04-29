package com.researchspace.figshare.rspaceadapter;

import com.researchspace.figshare.impl.FileOperationsImpl;
import com.researchspace.figshare.impl.FigshareTemplate;
import com.researchspace.figshare.impl.LoggingResponseErrorHandler;
import com.researchspace.figshare.model.FigshareCategory;
import com.researchspace.figshare.model.FigshareLicense;
import com.researchspace.repository.spi.IDepositor;
import com.researchspace.repository.spi.RepositoryOperationResult;
import com.researchspace.repository.spi.SubmissionMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Integration tests for FigshareRSpaceRepository using MockRestServiceServer.
 *
 * These tests reproduce the RSDEV-1081 bug: the adapter's submitDeposit flow
 * calls figshare-client-java POST endpoints that were missing Content-Type:
 * application/json, causing the Figshare API to reject requests and close the
 * connection with "java.io.IOException: stream is closed".
 */
public class FigshareRSpaceRepositoryMockServerTest {

    private static final String BASE_URL = "https://api.figshare.com/v2";
    private static final long ARTICLE_ID = 12345L;
    private static final long FILE_ID = 67890L;
    private static final String UPLOAD_TOKEN = "test-upload-token";
    private static final String UPLOAD_URL = "https://fup.figshare.com/upload/" + UPLOAD_TOKEN;

    private FigshareRSpaceRepository repo;
    private MockRestServiceServer mockServer;
    private File tempFile;

    @BeforeEach
    void setUp() throws IOException {
        RestTemplate restTemplate = new RestTemplate(
                new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));
        restTemplate.setErrorHandler(new LoggingResponseErrorHandler());

        FigshareTemplate figshareTemplate = new FigshareTemplate("test-token");
        figshareTemplate.setRestTemplate(restTemplate);
        figshareTemplate.setFileOps(new FileOperationsImpl(restTemplate, "test-token"));

        repo = new FigshareRSpaceRepository();
        repo.setFigshare(figshareTemplate);

        // Pre-populate categories and licenses to avoid additional GET requests
        repo.setCategories(new java.util.ArrayList<>(List.of(new FigshareCategory(25219L, 300L, "Algebra"))));
        try {
            repo.setFigshareLicenses(new java.util.ArrayList<>(List.of(
                    new FigshareLicense(new URL("https://creativecommons.org/licenses/by/4.0/"), "CC BY", 1, true))));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        mockServer = MockRestServiceServer.createServer(restTemplate);

        // small non-zip test file
        tempFile = File.createTempFile("figshare-adapter-test", ".dat");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), new byte[]{1, 2, 3, 4, 5});
    }

    /**
     * Reproduces RSDEV-1081: the POST to /account/articles must include
     * Content-Type: application/json. Before the fix in figshare-client-java 0.7.1
     * this header was absent and Figshare rejected the request, closing the
     * connection and causing IOException: stream is closed on the client.
     */
    @Test
    @DisplayName("submitDeposit sends Content-Type: application/json on article and file creation POSTs")
    void testSubmitDeposit_sendsCorrectContentTypeOnPostRequests() {
        expectCreateArticle();
        expectCreatePrivateLink();
        expectCreateFile();
        expectGetFileUploadInfo();
        expectGetUploadProcess();
        expectUploadPart();
        expectMarkFileUploadComplete();

        RepositoryOperationResult result = repo.submitDeposit(
                mockDepositor(), tempFile, testMetadata(), null);

        assertThat(result.isSucceeded()).isTrue();
        mockServer.verify();
    }

    /**
     * Verifies that a 500 response from the article creation endpoint does NOT cause
     * IOException: stream is closed (the RSDEV-1081 regression).
     *
     * Before figshare-client-java 0.7.1, the error handler consumed and closed the
     * response stream, then RestTemplate tried to read the closed stream, producing
     * RestClientException wrapping IOException: stream is closed.
     *
     * After the fix, the error handler no longer closes the stream prematurely.
     * createArticle() returns null (error is logged; error handler does not throw for 500),
     * which causes a NullPointerException downstream — but crucially, NOT stream is closed.
     */
    @Test
    @DisplayName("500 response on article creation does not cause IOException: stream is closed (RSDEV-1081)")
    void testSubmitDeposit_500ResponseDoesNotCauseStreamClosedException() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"Internal Server Error\", \"code\": 500}"));

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> repo.submitDeposit(mockDepositor(), tempFile, testMetadata(), null));

        // Walk the exception chain: none of the messages should contain "stream is closed"
        Throwable t = thrown;
        while (t != null) {
            assertThat(t.getMessage()).doesNotContain("stream is closed");
            t = t.getCause();
        }
        mockServer.verify();
    }

    // --- mock server expectation helpers ---

    private void expectCreateArticle() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"location\": \"" + BASE_URL + "/account/articles/" + ARTICLE_ID + "\"}"));
    }

    private void expectCreatePrivateLink() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles/" + ARTICLE_ID + "/private_links"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"location\": \"https://figshare.com/s/abc123\"}"));
    }

    private void expectCreateFile() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles/" + ARTICLE_ID + "/files"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"location\": \"" + BASE_URL + "/account/articles/" + ARTICLE_ID + "/files/" + FILE_ID + "\"}"));
    }

    private void expectGetFileUploadInfo() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles/" + ARTICLE_ID + "/files/" + FILE_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\": " + FILE_ID + ", \"upload_url\": \"" + UPLOAD_URL + "\","
                                + "\"upload_token\": \"" + UPLOAD_TOKEN + "\","
                                + "\"status\": \"pending\", \"name\": \"test.dat\", \"size\": 5}"));
    }

    private void expectGetUploadProcess() {
        mockServer.expect(requestTo(UPLOAD_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"token\": \"" + UPLOAD_TOKEN + "\","
                                + "\"parts\": [{\"partNo\": 1, \"startOffset\": 0, \"endOffset\": 4,"
                                + "\"status\": \"PENDING\", \"locked\": false}],"
                                + "\"status\": \"PENDING\"}"));
    }

    private void expectUploadPart() {
        mockServer.expect(requestTo(UPLOAD_URL + "/1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.OK).body(""));
    }

    private void expectMarkFileUploadComplete() {
        mockServer.expect(requestTo(BASE_URL + "/account/articles/" + ARTICLE_ID + "/files/" + FILE_ID))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED).body(""));
    }

    private IDepositor mockDepositor() {
        return new Depositor("test@example.com", "testuser", Collections.emptyList());
    }

    private SubmissionMetadata testMetadata() {
        SubmissionMetadata meta = new SubmissionMetadata();
        IDepositor depositor = mockDepositor();
        meta.setAuthors(List.of(depositor));
        meta.setContacts(List.of(depositor));
        meta.setTitle("Test deposit");
        meta.setDescription("Test description");
        meta.setSubjects(List.of("Algebra"));
        meta.setPublish(false);
        meta.setLicense(Optional.empty());
        return meta;
    }
}
