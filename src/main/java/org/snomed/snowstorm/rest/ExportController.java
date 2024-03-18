package org.snomed.snowstorm.rest;

import com.fasterxml.jackson.annotation.JsonView;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.drools.util.StringUtils;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.core.data.domain.jobs.ExportConfiguration;
import org.snomed.snowstorm.core.data.domain.jobs.ExportStatus;
import org.snomed.snowstorm.core.data.services.BranchMetadataKeys;
import org.snomed.snowstorm.core.data.services.ModuleDependencyService;
import org.snomed.snowstorm.core.rf2.export.ExportFilter;
import org.snomed.snowstorm.core.rf2.export.ExportService;
import org.snomed.snowstorm.rest.pojo.ExportRequestView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@Tag(name = "Export", description = "RF2")
@RequestMapping(value = "/exports", produces = "application/json")
public class ExportController {
	@Autowired
	private ExportService exportService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ModuleDependencyService moduleDependencyService;

    @Operation(summary = "Create an export job.",
			description = "Create a job to export an RF2 archive. " +
					"The 'location' response header contain the URL, including the identifier, of the new resource.")
	@PostMapping
	public ResponseEntity<Void> createExportJob(@Valid @RequestBody ExportRequestView exportRequestView) {
		String id = exportService.createJob(exportRequestView);
		if (exportRequestView.isStartExport()) {
			exportService.exportRF2ArchiveAsync(exportService.getExportJobOrThrow(id));
		}

		return ControllerHelper.getCreatedResponse(id);
	}

	@Operation(summary = "Retrieve an export job.")
	@GetMapping(value = "/{exportId}")
	public ExportConfiguration getExportJob(@PathVariable String exportId) {
		return exportService.getExportJobOrThrow(exportId);
	}

	@Operation(summary = "Download the RF2 archive from an export job.",
			description = "NOT SUPPORTED IN SWAGGER UI. Instead open the URL in a new browser tab or make a GET request another way. " +
					"This endpoint can only be called once per exportId.")
	@GetMapping(value = "/{exportId}/archive", produces="application/zip")
	public void downloadRf2Archive(@PathVariable String exportId, HttpServletResponse response) throws IOException {
		ExportConfiguration exportConfiguration = exportService.getExportJobOrThrow(exportId);
		if (!exportConfiguration.isStartExport()) {
			String filename = exportService.getFilename(exportConfiguration);
			response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
			exportService.exportRF2Archive(exportConfiguration, response.getOutputStream());
		} else {
			ExportStatus exportStatus = exportConfiguration.getStatus();
			if (Objects.equals(ExportStatus.COMPLETED, exportStatus)) {
				File archive = new File(exportConfiguration.getExportFilePath());
				if (archive.isFile()) {
					String filename = exportService.getFilename(exportConfiguration);
					response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
					exportService.copyRF2Archive(exportConfiguration, response.getOutputStream());
					return;
				} else {
					response.getWriter().write(String.format("Archive %s cannot be downloaded; possibly deleted during system restart.", exportConfiguration.getId()));
				}
			} else if (Objects.equals(ExportStatus.PENDING, exportStatus) || Objects.equals(ExportStatus.RUNNING, exportStatus)) {
				response.getWriter().write(String.format("Archive %s not ready for download; export in progress.", exportConfiguration.getId()));
			} else if (Objects.equals(ExportStatus.DOWNLOADED, exportStatus)) {
				response.getWriter().write(String.format("Archive %s previously downloaded; cannot re-download.", exportConfiguration.getId()));
			} else {
				response.getWriter().write(String.format("Export of archive %s failed; cannot download.", exportConfiguration.getId()));
			}

			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.getWriter().flush();
		}
	}
	
	@Operation(summary = "View a preview of the module dependency refset that would be generated for export")
	@GetMapping(value = "/module-dependency-preview")
	@JsonView(value = View.Component.class)
	public List<ReferenceSetMember> generateModuleDependencyPreview (
			@RequestParam String branchPath,
			@RequestParam String effectiveDate,
			@RequestParam(defaultValue="true") boolean isDelta,
			@RequestParam(required = false) Set<String> modulesIncluded) {
		
		//Need to detect if this is an Edition or Extension package so we know what MDRS rows to export
		//Extensions only mention their own modules, despite being able to "see" those on MAIN
		Branch branch = branchService.findBranchOrThrow(branchPath);
		final boolean isExtension = (branch.getMetadata() != null && !StringUtils.isEmpty(branch.getMetadata().getString(BranchMetadataKeys.DEPENDENCY_PACKAGE)));
		ExportFilter<ReferenceSetMember> exportFilter = rm -> moduleDependencyService.isExportable(rm, isExtension);
		
		return moduleDependencyService.generateModuleDependencies(branchPath, effectiveDate, modulesIncluded, isDelta, null)
				.stream()
				.filter(exportFilter::isValid)
				.collect(Collectors.toList());
	}
}
