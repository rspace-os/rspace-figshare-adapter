package com.researchspace.figshare.rspaceadapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.researchspace.figshare.api.Figshare;
import com.researchspace.figshare.model.*;
import com.researchspace.repository.spi.ExternalId;
import com.researchspace.repository.spi.IDepositor;
import com.researchspace.repository.spi.IdentifierScheme;
import com.researchspace.repository.spi.RepositoryOperationResult;
import com.researchspace.repository.spi.SubmissionMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.researchspace.core.util.TransformerUtils.toList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FigshareRSpaceRepositoryTest {

	private static final long PARENT_CATEGORY_ID = 3L;
	FigshareRSpaceRepositoryTSS repoAdapter;

	@Mock Figshare figshare;
	@Mock IDepositor author;
	static FigshareCategory C1 = new FigshareCategory(1L, PARENT_CATEGORY_ID, "c1");
	static FigshareCategory C2 = new FigshareCategory(2L, PARENT_CATEGORY_ID, "c2");
	final static List<FigshareCategory> categories = Arrays.asList(new FigshareCategory[] { C1, C2 });
	

	static class FigshareRSpaceRepositoryTSS extends FigshareRSpaceRepository {
		ArticlePost toPost = null;
		private boolean posted;

		@Override
		RepositoryOperationResult doPost(File toDeposit, ArticlePost toPost, SubmissionMetadata metadata) {
			this.toPost = toPost;
			this.posted = true;
			return null;
		}
	}

	@BeforeEach
	public void setUp() throws Exception {
		MockitoAnnotations.initMocks(this);
		repoAdapter = new FigshareRSpaceRepositoryTSS();
		repoAdapter.setFigshare(figshare);
	}

	@Test
	 void testSubmitDeposit() {
		SubmissionMetadata meta = createAMetaDataWithOrcidId();
		meta.setSubjects(toList(C2.getTitle()));

		when(figshare.getCategories(false)).thenReturn(categories);
		repoAdapter.submitDeposit(null, new File("any"), meta, null);
		assertTrue(repoAdapter.posted);
		assertThat(repoAdapter.toPost.getCategories()).anyMatch(c -> c == C2.getId().intValue());
	}

	@Test
	@DisplayName("Unknown category handled gracefully")
	 void testSubmitDepositUnknownCategory() {
		SubmissionMetadata meta = createAMetaDataWithOrcidId();
		meta.setSubjects(toList("UNKNOWN"));

		when(figshare.getCategories(false)).thenReturn(categories);
		repoAdapter.submitDeposit(null, new File("any"), meta, null);
		assertTrue(repoAdapter.posted);
		assertThat(repoAdapter.toPost.getCategories()).anyMatch(c -> c == FigshareCategory.SOFTWARE_TESTING.getId());
		
	}
	
	@Test
	@DisplayName("Default license used if none specified.")
	 void testSubmitDepositWithNoLicense() {
		SubmissionMetadata meta = createAMetaDataWithOrcidId();
		meta.setSubjects(toList("UNKNOWN"));

		when(figshare.getCategories(false)).thenReturn(categories);
		repoAdapter.submitDeposit(null, new File("any"), meta, null);
		assertTrue(repoAdapter.posted);
		final int EXPECTED_DEFAULT_LICENSE_VALUE = 1;
		assertThat(repoAdapter.toPost.getLicense().intValue()).isEqualTo(EXPECTED_DEFAULT_LICENSE_VALUE);
		
	}

	@Test
	void urlRetrievalPreferentiallyRetrievesPrivateUrl() throws MalformedURLException {
		ArticlePresenter figshareArticle = ArticlePresenter.builder()
				.privateURL(new URL("https://somewhere-private.com"))
				.publicURL(new URL("https://somewhere-public.com"))
				.build();
		Location loc = new Location(new URL("https://privatelocation.com/1234") ,emptyList(),"1234");
		when(figshare.createPrivateArticleLink(loc.getId())).thenThrow(RestClientException.class);
		when(figshare.getArticle(loc.getId())).thenReturn(figshareArticle);
		URL link = repoAdapter.getLinkToArticle(loc);
		verify(figshare).getArticle(loc.getId());
		assertEquals(figshareArticle.getPrivateURL(), link);
	}

	@Test
	void urlRetrievalFallsBackToPublicPublicUrl() throws MalformedURLException {
		ArticlePresenter figshareArticle = ArticlePresenter.builder()
				.publicURL(new URL("https://somewhere-public.com"))
				.build();
		Location loc = new Location(new URL("https://somewhere-on-figshare.com/1234") ,emptyList(),"1234");
		when(figshare.createPrivateArticleLink(loc.getId())).thenThrow(RestClientException.class);
		when(figshare.getArticle(loc.getId())).thenReturn(figshareArticle);
		URL link = repoAdapter.getLinkToArticle(loc);
		verify(figshare).getArticle(loc.getId());
		assertEquals(figshareArticle.getPublicURL(), link);
	}

	@Test
	void nullUrlCanBeReturned() throws MalformedURLException {
		ArticlePresenter figshareArticle = new ArticlePresenter();
		Location loc = new Location(new URL("https://somewhere-on-figshare.com/1234") ,emptyList(),"1234");
		when(figshare.createPrivateArticleLink(loc.getId())).thenThrow(RestClientException.class);
		when(figshare.getArticle(loc.getId())).thenReturn(figshareArticle);
		URL link = repoAdapter.getLinkToArticle(loc);
		verify(figshare).getArticle(loc.getId());
		assertNull(link);
	}

	private SubmissionMetadata createAMetaDataWithOrcidId() {
		List<ExternalId> ids = new ArrayList<>();
		ids.add(new ExternalId(IdentifierScheme.ORCID, "1234"));
		SubmissionMetadata md = new SubmissionMetadata();
		when(author.getEmail()).thenReturn("email@somewhere.com");
		when(author.getUniqueName()).thenReturn("anyone");
		when(author.getExternalIds()).thenReturn(ids);

		md.setAuthors(Collections.singletonList(author));
		md.setContacts(Collections.singletonList(author));
		md.setDescription("desc");
		md.setPublish(false);
		md.setSubjects(toList("subject"));
		md.setTitle("title");
		return md;
	}

	@Test
	 void testStaticConfigurationOfFigshare() throws IOException {
		String categoriesJson = new String(Files.readAllBytes(Paths.get("src/test/resources/categories.json")));
		String licensesJson = new String(Files.readAllBytes(Paths.get("src/test/resources/licenses.json")));
		repoAdapter.setStaticFigshareConfig(licensesJson, categoriesJson);
		List<FigshareCategory> categories = repoAdapter.getFigshareCategories();
		List<FigshareLicense> licenses = repoAdapter.getFigshareLicenses();

		assertTrue(categories.size() == 5);
		assertTrue(categories.stream().anyMatch(s -> s.getTitle().equals("Science")));

		assertTrue(licenses.size() == 17);
		assertTrue(licenses.stream().anyMatch(l -> l.getName().equals("CC BY-SA 4.0")));

	}

	@Test
	 void testToSubjectsToLicensesThrowsException() {
		String emptyValidJson = "{}";
		String emptyString = "";
		//Empty Valid JSON throws JsonProcessingException
		assertThrows(JsonProcessingException.class, () -> repoAdapter.setStaticFigshareConfig(emptyValidJson, emptyValidJson));
		//Empty String throws JsonProcessingException
		assertThrows(JsonProcessingException.class, () -> repoAdapter.setStaticFigshareConfig(emptyString, emptyString));
		//Null json throws IAE
		assertThrows(IllegalArgumentException.class, () -> repoAdapter.setStaticFigshareConfig(null, null));
	}
}
