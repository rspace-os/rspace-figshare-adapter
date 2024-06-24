package com.researchspace.figshare.rspaceadapter;

import com.researchspace.figshare.api.Figshare;
import com.researchspace.repository.spi.IDepositor;
import com.researchspace.repository.spi.RepositoryOperationResult;
import com.researchspace.repository.spi.SubmissionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@ContextConfiguration(classes = { SpringTestConfig.class })
@TestPropertySource(locations = "classpath:/test.properties")
@Slf4j
public class FigshareRSpaceRepositoryAcceptanceTest extends AbstractJUnit4SpringContextTests {

	@Autowired
	FigshareRSpaceRepository repo;
	@Autowired
	private Figshare figshare;

	File zip = new File("src/test/resources/HTMLExportWithAttachments.zip");

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testGetConfigurer() {
		assertNotNull(repo.getConfigurer());
		assertThat(repo.getConfigurer().getSubjects()).size().isGreaterThan(20);
	}

	@Ignore("requires figshareToken to be set in test.properties")
	@Test
	public void testAccount() {
		log.info(figshare.account().toString());
	}

	@Ignore("requires figshareToken to be set in test.properties")
	@Test
	public void testUploadZipHTMLArchive() {
		IDepositor depositor = new Depositor("x@y.com", "xxxxxx", Collections.emptyList());
		SubmissionMetadata metadata = new SubmissionMetadata();
		metadata.setAuthors(List.of(depositor));
		metadata.setContacts(List.of(depositor));
		metadata.setDescription("desc");
		metadata.setPublish(false);
		metadata.setSubjects(List.of("Algebra"));
		metadata.setTitle("title");
		metadata.setLicense(Optional.of(licenseUrl()));
		RepositoryOperationResult result = repo.submitDeposit(depositor, zip, metadata, null);
		assertTrue(result.isSucceeded());
		// this is the URL of the new deposit in Figshare
		assertNotNull(result.getUrl());
	}

	private URL licenseUrl() {
		try {
			return new URL("https://www.gnu.org/licenses/gpl-3.0.html");
		} catch (MalformedURLException e) {
			return null;
		}
	}

}
