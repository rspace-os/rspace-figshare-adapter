# Agent Guidelines

## Running Tests

Standard unit and mock-server tests can be run without credentials:

```
mvn test
```

When verifying a fix that touches the HTTP layer or the deposit flow, also run the integration tests in `FigshareRSpaceRepositoryAcceptanceTest`. These are `@Ignore`d by default because they require a real Figshare API token:

1. Set the token in `src/test/resources/test.properties`:
   ```
   figshareToken=<your-token>
   ```
2. Remove the `@Ignore` annotations from `testAccount` and `testUploadZipHTMLArchive`.
3. Run:
   ```
   mvn test -Dtest="FigshareRSpaceRepositoryAcceptanceTest"
   ```
4. Restore `@Ignore` and clear the token before committing — do not commit credentials.
