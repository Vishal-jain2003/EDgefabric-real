package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.dto.export.DashboardExportResponse;
import com.edgefabric.loadbalancer.service.DashboardCsvFormatter;
import com.edgefabric.loadbalancer.service.DashboardExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Tag(name = "Dashboard", description = "Export dashboard data for external analysis")
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardExportController {

    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final DashboardExportService exportService;
    private final DashboardCsvFormatter csvFormatter;

    public DashboardExportController(DashboardExportService exportService,
                                     DashboardCsvFormatter csvFormatter) {
        this.exportService = exportService;
        this.csvFormatter = csvFormatter;
    }

    @Operation(summary = "Export dashboard snapshot", description = "Downloads a snapshot of ring info, LB health, and gossip state as JSON or CSV")
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "json") String format) {
        if (!"json".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Invalid export format: '" + format + "'. Supported formats: json, csv");
        }

        DashboardExportResponse snapshot = exportService.buildSnapshot();
        String timestamp = FILENAME_TS.format(Instant.now());

        if ("csv".equalsIgnoreCase(format)) {
            String csv = csvFormatter.format(snapshot);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=dashboard-export_" + timestamp + ".csv")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=dashboard-export_" + timestamp + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(snapshot);
    }
}
