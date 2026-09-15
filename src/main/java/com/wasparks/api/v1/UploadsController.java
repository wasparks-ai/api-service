package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Recipient CSV upload (§B3): the third way to give a campaign its audience, for lists too large or too
 * awkward to send as JSON.
 *
 * <h2>Streamed, not buffered</h2>
 * The file goes from the socket to tenants-service to GCS without a copy of it living on this service's
 * heap. That is a deliberate constraint of the hand-off and not an optimisation: this is a gateway whose
 * steady state is small JSON bodies, and a handful of partners bulk-loading 10 MB lists at once would
 * otherwise put tens of megabytes of short-lived garbage through a heap sized for kilobytes.
 *
 * <h2>The upload id is the object key</h2>
 * There is no upload table. {@code uploadId} is {@code "upl_"} plus the base64url of the GCS object key,
 * and the tenant check upstream is a prefix check on the decoded key — so a forged id can only ever
 * address the caller's own folder (internal.md, CSV upload). Nothing here needs to know that, with one
 * exception: an id that no longer resolves is {@code 404 upload_not_found} rather than an error about a
 * missing row, because uploads live 24 hours under a bucket lifecycle rule and an expired one is
 * genuinely indistinguishable from one that never existed.
 */
@RestController
@RequestMapping("/v1/uploads")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Uploads",
        description = "Upload a recipient CSV and use its id as a campaign audience.")
public class UploadsController {

    private final InternalTenantsClient tenantsClient;

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Upload a recipient CSV",
            description = """
                    `multipart/form-data` with one part named `file`. Up to 10 MB.

                    Returns `{uploadId, rows, columns, fileName}`. Quote the `uploadId` on
                    `POST /v1/campaigns` as `audience.csvUploadId`, together with a `mapping` that says
                    which column is the phone number and which column fills each placeholder:

                    ```json
                    { "audience": { "csvUploadId": "upl_…",
                                    "mapping": { "phoneColumn": "mobile",
                                                 "vars": { "1": "first_name", "2": "property" } } } }
                    ```

                    This is the one place variables are mapped by **column name** rather than given
                    per recipient — the file already has the columns, so restating them per row would be
                    the same data twice.

                    **Uploads expire after 24 hours.** Create the campaign the same day; an expired id
                    comes back as `404 upload_not_found`.""")
    public JsonNode uploadCsv(@RequestParam("file") MultipartFile file) {
        ApiPrincipal principal = CurrentPrincipal.api();
        if (file == null || file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Send the CSV as a multipart part named `file`.");
        }

        // try-with-resources on the container's own stream: past this point the bytes belong to the
        // upstream request, and leaving the spool file open would hold a file descriptor per upload
        // until the next GC noticed.
        try (InputStream content = file.getInputStream()) {
            return tenantsClient.uploadCsv(principal, fileName(file), file.getSize(), content);
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (IOException e) {
            // The client went away mid-upload, or the spool file could not be read. Neither is the
            // partner's mistake to fix and neither says anything about the file's contents.
            log.warn("Could not read an uploaded CSV for tenant {}: {}", principal.tenantId(),
                    e.getMessage());
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                    "The upload did not complete. Send the file again.");
        }
    }

    /**
     * A filename we are willing to put in a multipart header.
     *
     * <p>The original is a client-supplied string that ends up in an HTTP header and, upstream, in an
     * object key. Stripping path separators and quotes keeps it from being either a header injection or
     * a way to write outside the tenant's own prefix; a blank one gets a default rather than an error,
     * because the name is a convenience and the file is the point.
     */
    private String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "recipients.csv";
        }
        String cleaned = original.replaceAll("[\\\\/\\r\\n\"]", "").trim();
        if (cleaned.isEmpty()) {
            return "recipients.csv";
        }
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }
}
