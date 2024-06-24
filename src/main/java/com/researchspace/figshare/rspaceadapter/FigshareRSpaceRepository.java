package com.researchspace.figshare.rspaceadapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchspace.figshare.api.Figshare;
import com.researchspace.figshare.impl.FigshareTemplate;
import com.researchspace.figshare.model.*;
import com.researchspace.figshare.model.ArticlePost.ArticlePostBuilder;
import com.researchspace.repository.spi.IDepositor;
import com.researchspace.repository.spi.IRepository;
import com.researchspace.repository.spi.License;
import com.researchspace.repository.spi.LicenseConfigInfo;
import com.researchspace.repository.spi.LicenseDef;
import com.researchspace.repository.spi.RepositoryConfig;
import com.researchspace.repository.spi.RepositoryConfigurer;
import com.researchspace.repository.spi.RepositoryOperationResult;
import com.researchspace.repository.spi.Subject;
import com.researchspace.repository.spi.SubmissionMetadata;
import com.researchspace.zipprocessing.ArchiveIterator;
import com.researchspace.zipprocessing.ArchiveIteratorImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Comparator.comparing;
import static org.apache.commons.io.FilenameUtils.getExtension;

@Slf4j
public class FigshareRSpaceRepository implements IRepository, RepositoryConfigurer {

    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String ARCHIVE_RESOURCE_FOLDER = "/resources/";

    private Figshare figshare;

    private ArchiveIterator archiveIterator;

    private List<Subject> subjects = new ArrayList<>();

    private List<FigshareCategory> categories = new ArrayList<>();

    private List<License> licenses = new ArrayList<>();

    private List<FigshareLicense> figshareLicenses = new ArrayList<>();

    public FigshareRSpaceRepository() {
        this.archiveIterator = new ArchiveIteratorImpl();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Set a configured FigshareTemplate
     *
     * @param figshare
     */
    public void setFigshare(Figshare figshare) {
        this.figshare = figshare;
    }

    /**
     * Creates a new FigshareAPI template using access token in {@code}config.getIdentifier(){@config}
     *
     * @param config
     */
    @Override
    public void configure(RepositoryConfig config) {
        this.figshare = new FigshareTemplate(config.getIdentifier());
    }

    @Override
    public RepositoryOperationResult submitDeposit(IDepositor depositor, File toDeposit,
                                                   SubmissionMetadata metadata, RepositoryConfig repoCfg) {
        log.info("Depositing file {} of size {} ", toDeposit.getAbsolutePath(), toDeposit.length());
        ArticlePostBuilder articleBuilder = ArticlePost.builder();
        articleBuilder.title(metadata.getTitle()).description(metadata.getDescription());
        for (IDepositor author : metadata.getAuthors()) {
            Author figAuthor = new Author(author.getUniqueName(), null);
            log.info("Submitting author details: {}", figAuthor);
            articleBuilder.author(figAuthor);
        }
        List<FigshareCategory> subjects = getFigshareCategories();
        FigshareCategory matching = FigshareCategory.SOFTWARE_TESTING;
        if (!metadata.getSubjects().isEmpty()) {
            matching = subjects.stream().filter(cat -> cat.getTitle().equals(metadata.getSubjects().get(0))).findFirst()
                    .orElse(FigshareCategory.SOFTWARE_TESTING);
        }
        articleBuilder.category(matching.getId());
        // tag required for publishing to work, if needed.
        articleBuilder.tags(Arrays.asList("RSpace"));
        List<FigshareLicense> allLicenses = getFigshareLicenses();
        Optional<FigshareLicense> matchingLicense = allLicenses.stream()
                .filter(figshareLicense -> metadata.getLicense().isPresent() && figshareLicense.getUrl().equals(metadata.getLicense().get())).findFirst();
        FigshareLicense toSet = matchingLicense.orElse(getDefaultLicense());
        articleBuilder.license(toSet.getValue());
        ArticlePost toPost = articleBuilder.build();

        return doPost(toDeposit, toPost, metadata);
    }

    private FigshareLicense getDefaultLicense() {
        try {
            return new FigshareLicense(new URL(Figshare.DEFAULT_LICENSE_URL), "CC BY", 1, true);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return null;
        }
    }

    RepositoryOperationResult doPost(File toDeposit, ArticlePost toPost, SubmissionMetadata metadata) {
        try {
            log.info("Article to post: {}", toPost);
            Location articleId = figshare.createArticle(toPost);
            URL link = getLinkToArticle(articleId);
            uploadExport(toDeposit, articleId);
            String feedbackMsg = "Deposit succeeded.";
            log.info("New article will be at URL {}", link);

            if (metadata.isPublish()) {
                FigshareResponse<Location> published = figshare.publishArticle(articleId.getId());
                String publishingFeedback;
                if (published.hasError()) {
                    publishingFeedback = String.format(" Publishing failed:  %s", published.getError().getMessage());
                } else {
                    publishingFeedback = "Publishing succeeded";
                    link = published.getData().getLocation();
                }
                feedbackMsg = feedbackMsg + publishingFeedback;
            }
            return new RepositoryOperationResult(true, feedbackMsg, link);
        } catch (RestClientException e) {
            log.error("Couldn't perform  Figshare API operation : {}", e.getMessage());
            return new RepositoryOperationResult(false, "Submission failed - " + e.getMessage(), null);
        } catch (IOException e) {
            log.error("IO error during zip archive traversal. Figshare upload may not be complete :{}", e.getMessage());
            return new RepositoryOperationResult(false, "Submission failed - " + e.getMessage(), null);
        }

    }

    // this attempts to fix rspac-2566.
    // We're not sure why Figshare provides a private article link, as well as 2 other
    // additional URLs  to access the article.
    // Here we attempt to get links in order:
    // 1 privateArticleLink (this is what has historically been used; requires Figshare account to be linked to Orcid)
    // 2 privateUrl - a guess that this is more likely to be viewable than public URL if the item hasn't been published
    // 3 public URL
    URL getLinkToArticle(Location articleId) {
        URL link;
        try {
            PrivateArticleLink privateLink = figshare.createPrivateArticleLink(articleId.getId());
            link = privateLink.getWeblink();
        } catch (RestClientException e) { // this can result in 403 unauthorised
            log.warn("Couldn't create private link for article {} - account needs Orcid id? {}", articleId.getId(), e.getMessage());
            log.info("Attempting to get private URL");
            ArticlePresenter article = figshare.getArticle(articleId.getId());
            link = article.getPrivateURL();
            if (link == null) {
                log.info("Couldn't get a private URL, attempting to get public URL");
                link = article.getPublicURL();
            }
            if (link == null) {
                log.warn("Could not find any HTTP link for the created article, id: {} ", articleId.getId());
            }
        }
        return link;
    }

    private void uploadExport(File toDeposit, Location articleId) throws IOException {
        if ("zip".equals(getExtension(toDeposit.getName()))) {
            log.info("Uploading main zip as single zip archive...");
            figshare.uploadFile(articleId.getId(), toDeposit);
            archiveIterator.processZip(toDeposit, file -> figshare.uploadFile(articleId.getId(), file),
                    entry -> !entry.getName().contains(ARCHIVE_RESOURCE_FOLDER));

        } else {
            figshare.uploadFile(articleId.getId(), toDeposit);
        }
    }


    @Override
    public RepositoryOperationResult testConnection() {
        try {
            if (figshare.test()) {
                return new RepositoryOperationResult(true, "Test connection OK!", null);
            } else {
                return new RepositoryOperationResult(false, "Test connection failed - please check settings.", null);
            }
        } catch (RestClientException e) {
            log.error("Couldn't perform test action {}" + e.getMessage());
            return new RepositoryOperationResult(false, "Test connection failed - " + e.getMessage(), null);
        }
    }

    @Override
    public RepositoryConfigurer getConfigurer() {
        return this;
    }

    @Override
    public List<Subject> getSubjects() {
        if (subjects.isEmpty()) {
            List<FigshareCategory> categories = getFigshareCategories();
            categories.forEach(c -> subjects.add(new Subject(c.getTitle())));
        }
        subjects.sort(comparing(Subject::getName));
        return Collections.unmodifiableList(subjects);
    }

    @Override
    public LicenseConfigInfo getLicenseConfigInfo() {
        if (licenses.isEmpty()) {
            List<FigshareLicense> figshareLicenses = getFigshareLicenses();
            figshareLicenses.forEach(license -> licenses.add(new License(new LicenseDef(license.getUrl(), license.getName()), license.isDefaultLicense())));
        }
        licenses.sort(this::compareLicense);
        return new LicenseConfigInfo(true, false, Collections.unmodifiableList(licenses));
    }

    public List<FigshareCategory> getFigshareCategories() {
        if (categories.isEmpty()) {
            List<FigshareCategory> categories = figshare.getCategories(false);
            setCategories(categories);
        }
        categories.sort(comparing(FigshareCategory::getTitle));
        return Collections.unmodifiableList(categories);
    }

    public List<FigshareLicense> getFigshareLicenses() {
        if (figshareLicenses.isEmpty()) {
            setFigshareLicenses(figshare.getLicenses(false));
        }
        figshareLicenses.sort(this::compareFigshareLicense);
        return figshareLicenses;
    }

    public int compareLicense(License o1, License o2) {
        return o1.getLicenseDefinition().getName().compareTo(o2.getLicenseDefinition().getName());
    }

    public int compareFigshareLicense(FigshareLicense o1, FigshareLicense o2) {
        return o1.getName().compareTo(o2.getName());
    }

    public void setSubjects(List<Subject> subjects) {
        this.subjects = subjects;
    }

    public void setFigshareLicenses(List<FigshareLicense> licenses) {
        this.figshareLicenses = licenses;
    }

    public void setCategories(List<FigshareCategory> categories) {
        this.categories = categories;
    }

    public void setStaticFigshareConfig(String licensesJson, String categoriesJson) throws JsonProcessingException {
        setCategories(objectMapper.readValue(categoriesJson, new TypeReference<>() {
        }));
        setFigshareLicenses(objectMapper.readValue(licensesJson, new TypeReference<>() {
        }));
    }

}
