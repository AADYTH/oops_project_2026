package com.cropscanner.folix;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of newest-first FOLIX scan reports. Wraps Spring Data's
 * {@link Page} in a fixed, explicit shape rather than returning it (or the
 * entity) directly, for the same reason as {@link FolixReportResponse}.
 */
public record FolixReportPage(
        List<FolixReportResponse> reports,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static FolixReportPage from(Page<FolixScanReportEntity> page) {
        return new FolixReportPage(
                page.getContent().stream().map(FolixReportResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
