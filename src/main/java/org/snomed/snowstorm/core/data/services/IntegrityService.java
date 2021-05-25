package org.snomed.snowstorm.core.data.services;

import ch.qos.logback.classic.Level;
import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.*;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.Commit;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.conversion.ConversionException;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.pojo.IntegrityIssueReport;
import org.snomed.snowstorm.core.util.TimerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.*;

import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.snomed.snowstorm.core.data.domain.ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION;
import static org.snomed.snowstorm.core.data.services.BranchMetadataHelper.INTERNAL_METADATA_KEY;

@Service
public class IntegrityService extends ComponentService implements CommitListener {

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private AxiomConversionService axiomConversionService;

	@Autowired
	private BranchMetadataHelper branchMetadataHelper;

	@Autowired
	private DescriptionService descriptionService;

	public static final String INTEGRITY_ISSUE_METADATA_KEY = "integrityIssue";

	private Logger logger = LoggerFactory.getLogger(getClass());

	@Override
	public void preCommitCompletion(Commit commit) throws IllegalStateException {
		if (commit.isRebase()) {
			return;
		}
		Map<String, String> internalMap = commit.getBranch().getMetadata().getMapOrCreate(INTERNAL_METADATA_KEY);
		final String integrityIssueString = internalMap.get(INTEGRITY_ISSUE_METADATA_KEY);
		if (Boolean.parseBoolean(integrityIssueString)) {
			try {
				BranchCriteria branchCriteriaIncludingOpenCommit = versionControlHelper.getBranchCriteriaIncludingOpenCommit(commit);
				IntegrityIssueReport integrityIssueReport = findChangedComponentsWithBadIntegrity(branchCriteriaIncludingOpenCommit, commit.getBranch());
				if (integrityIssueReport.isEmpty()) {
					internalMap.remove(INTEGRITY_ISSUE_METADATA_KEY);
					logger.info("No integrity issue found on branch {} after commit {}", commit.getBranch().getPath(), commit.getTimepoint().getTime());
				}
			} catch (ServiceException e) {
				logger.error("Integrity check didn't complete successfully.", e);
			}
		}
	}

	public IntegrityIssueReport findChangedComponentsWithBadIntegrity(Branch branch) throws ServiceException {
		return  findChangedComponentsWithBadIntegrity(versionControlHelper.getBranchCriteria(branch), branch);
	}

	public IntegrityIssueReport findChangedComponentsWithBadIntegrity(BranchCriteria branchCriteria, Branch branch) throws ServiceException {

		if (branch.getPath().equals("MAIN")) {
			throw new RuntimeServiceException("This function can not be used on the MAIN branch. " +
					"Please use the full integrity check instead.");
		}

		TimerUtil timer = new TimerUtil("Changed component integrity check on " + branch.getPath(), Level.INFO, 1);

		final Map<Long, Long> relationshipWithInactiveSource = new Long2LongOpenHashMap();
		final Map<Long, Long> relationshipWithInactiveType = new Long2LongOpenHashMap();
		final Map<Long, Long> relationshipWithInactiveDestination = new Long2LongOpenHashMap();
		final Map<String, Set<Long>> axiomWithInactiveReferencedConcept = new HashMap<>();

		// Find any active stated relationships using the concepts which have been deleted or inactivated on this branch
		// First find those concept
		Set<Long> deletedOrInactiveConcepts = findDeletedOrInactivatedConcepts(branch, branchCriteria);
		timer.checkpoint("Collect deleted or inactive concepts: " + deletedOrInactiveConcepts.size());

		// Then find the relationships with bad integrity
		try (SearchHitsIterator<Relationship> badRelationshipsStream = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteria.getEntityBranchCriteria(Relationship.class))
								.must(termsQuery(Relationship.Fields.ACTIVE, true))
								.mustNot(termsQuery(Relationship.Fields.CHARACTERISTIC_TYPE_ID, Concepts.INFERRED_RELATIONSHIP))
								.must(boolQuery()
										.should(termsQuery(Relationship.Fields.SOURCE_ID, deletedOrInactiveConcepts))
										.should(termsQuery(Relationship.Fields.TYPE_ID, deletedOrInactiveConcepts))
										.should(termsQuery(Relationship.Fields.DESTINATION_ID, deletedOrInactiveConcepts))
								)
						)
						.withPageable(LARGE_PAGE).build(),
				Relationship.class)) {
			badRelationshipsStream.forEachRemaining(hit -> {
				Relationship relationship = hit.getContent();
				if (deletedOrInactiveConcepts.contains(parseLong(relationship.getSourceId()))) {
					relationshipWithInactiveSource.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getSourceId()));
				}
				if (deletedOrInactiveConcepts.contains(parseLong(relationship.getTypeId()))) {
					relationshipWithInactiveType.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getTypeId()));
				}
				if (deletedOrInactiveConcepts.contains(parseLong(relationship.getDestinationId()))) {
					relationshipWithInactiveDestination.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getDestinationId()));
				}
			});
		}
		timer.checkpoint("Collect changed relationships referencing deleted or inactive concepts: " +
				(relationshipWithInactiveSource.size() + relationshipWithInactiveType.size() + relationshipWithInactiveDestination.size()));

		// Then find axioms with bad integrity using the stated semantic index
		Set<Long> conceptIdsWithBadAxioms = new LongOpenHashSet();
		try (SearchHitsIterator<QueryConcept> badStatedIndexConcepts = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteria.getEntityBranchCriteria(QueryConcept.class))
								.must(termQuery(QueryConcept.Fields.STATED, true))
								.must(termsQuery(QueryConcept.Fields.ATTR + "." + QueryConcept.ATTR_TYPE_WILDCARD, deletedOrInactiveConcepts))
						)
						.withPageable(LARGE_PAGE).build(),
				QueryConcept.class)) {
			badStatedIndexConcepts.forEachRemaining(hit -> conceptIdsWithBadAxioms.add(hit.getContent().getConceptIdL()));
		}

		Map<String, String> axiomIdReferenceComponentMap = new HashMap<>();
		if (!conceptIdsWithBadAxioms.isEmpty()) {
			try (SearchHitsIterator<ReferenceSetMember> possiblyBadAxioms = elasticsearchTemplate.searchForStream(
					new NativeSearchQueryBuilder()
							.withQuery(boolQuery()
									.must(branchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
									.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
									.must(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.OWL_AXIOM_REFERENCE_SET))
									.must(termsQuery(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID, conceptIdsWithBadAxioms))
							)
							.withPageable(LARGE_PAGE).build(),
					ReferenceSetMember.class)) {
				try {
					while (possiblyBadAxioms.hasNext()) {
						ReferenceSetMember axiomMember = possiblyBadAxioms.next().getContent();
						String owlExpression = axiomMember.getAdditionalField(OWL_EXPRESSION);
						Set<Long> referencedConcepts = axiomConversionService.getReferencedConcepts(owlExpression);
						Sets.SetView<Long> badReferences = Sets.intersection(referencedConcepts, deletedOrInactiveConcepts);
						if (!badReferences.isEmpty()) {
							axiomIdReferenceComponentMap.put(axiomMember.getId(), axiomMember.getReferencedComponentId());
							axiomWithInactiveReferencedConcept.computeIfAbsent(axiomMember.getId(), id -> new HashSet<>()).addAll(badReferences);
						}
					}
				} catch (ConversionException e) {
					throw new ServiceException("Failed to deserialize axiom during reference integrity check.", e);
				}
			}
		}

		// Gather all the concept ids used in active axioms and stated relationships which have been changed on this task
		Map<Long, Set<Long>> conceptUsedAsSourceInRelationships = new Long2ObjectOpenHashMap<>();
		Map<Long, Set<Long>> conceptUsedAsTypeInRelationships = new Long2ObjectOpenHashMap<>();
		Map<Long, Set<Long>> conceptUsedAsDestinationInRelationships = new Long2ObjectOpenHashMap<>();
		Map<Long, Set<String>> conceptUsedInAxioms = new Long2ObjectOpenHashMap<>();
		try (SearchHitsIterator<Relationship> relationshipStream = elasticsearchTemplate.searchForStream(new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(versionControlHelper.getBranchCriteriaUnpromotedChanges(branch).getEntityBranchCriteria(Relationship.class))
								.must(termQuery(Relationship.Fields.ACTIVE, true))
								.mustNot(termsQuery(Relationship.Fields.CHARACTERISTIC_TYPE_ID, Concepts.INFERRED_RELATIONSHIP))
						)
						.withPageable(LARGE_PAGE)
						.build(),
				Relationship.class)) {
			relationshipStream.forEachRemaining(hit -> {
				Relationship relationship = hit.getContent();
				long relationshipId = parseLong(relationship.getRelationshipId());
				conceptUsedAsSourceInRelationships.computeIfAbsent(parseLong(relationship.getSourceId()), id -> new LongOpenHashSet()).add(relationshipId);
				conceptUsedAsTypeInRelationships.computeIfAbsent(parseLong(relationship.getTypeId()), id -> new LongOpenHashSet()).add(relationshipId);
				if (!relationship.isConcrete()) {
					conceptUsedAsDestinationInRelationships.computeIfAbsent(parseLong(relationship.getDestinationId()), id -> new LongOpenHashSet()).add(relationshipId);
				}
			});
		}
		try (SearchHitsIterator<ReferenceSetMember> axiomStream = elasticsearchTemplate.searchForStream(new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(versionControlHelper.getBranchCriteriaUnpromotedChanges(branch).getEntityBranchCriteria(ReferenceSetMember.class))
								.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
								.must(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.OWL_AXIOM_REFERENCE_SET))
						)
						.withPageable(LARGE_PAGE)
						.build(),
				ReferenceSetMember.class)) {
			try {
				while (axiomStream.hasNext()) {
					ReferenceSetMember axiom = axiomStream.next().getContent();
					axiomIdReferenceComponentMap.put(axiom.getId(), axiom.getReferencedComponentId());
					Set<Long> referencedConcepts = axiomConversionService.getReferencedConcepts(axiom.getAdditionalField(OWL_EXPRESSION));
					for (Long referencedConcept : referencedConcepts) {
						conceptUsedInAxioms.computeIfAbsent(referencedConcept, id -> new HashSet<>()).add(axiom.getId());
					}
				}
			} catch (ConversionException e) {
				throw new ServiceException("Failed to deserialise axiom during reference integrity check.", e);
			}
		}

		// Of these concepts which are active?
		Set<Long> conceptsRequiredActive = new LongOpenHashSet();
		conceptsRequiredActive.addAll(conceptUsedAsSourceInRelationships.keySet());
		conceptsRequiredActive.addAll(conceptUsedAsTypeInRelationships.keySet());
		conceptsRequiredActive.addAll(conceptUsedAsDestinationInRelationships.keySet());
		conceptsRequiredActive.addAll(conceptUsedInAxioms.keySet());
		timer.checkpoint("Collect concepts referenced in changed relationships and axioms: " + conceptsRequiredActive.size());

		Set<Long> activeConcepts = new LongOpenHashSet();
		try (SearchHitsIterator<Concept> activeConceptStream = elasticsearchTemplate.searchForStream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(Concept.class))
						.must(termQuery(Concept.Fields.ACTIVE, true))
						.must(termsQuery(Concept.Fields.CONCEPT_ID, conceptsRequiredActive))
				)
				.withFields(Concept.Fields.CONCEPT_ID)
				.build(), Concept.class)) {
			activeConceptStream.forEachRemaining(hit -> activeConcepts.add(hit.getContent().getConceptIdAsLong()));
		}
		timer.checkpoint("Collect active concepts referenced in changed relationships and axioms: " + activeConcepts.size());

		// If any concepts not active add the relationships which use them to the report because they have bad integrity
		Set<Long> conceptsNotActive = new LongOpenHashSet(conceptsRequiredActive);
		conceptsNotActive.removeAll(activeConcepts);
		for (Long conceptNotActive : conceptsNotActive) {
			for (Long relationshipId : conceptUsedAsSourceInRelationships.getOrDefault(conceptNotActive, Collections.emptySet())) {
				relationshipWithInactiveSource.put(relationshipId, conceptNotActive);
			}
			for (Long relationshipId : conceptUsedAsTypeInRelationships.getOrDefault(conceptNotActive, Collections.emptySet())) {
				relationshipWithInactiveType.put(relationshipId, conceptNotActive);
			}
			for (Long relationshipId : conceptUsedAsDestinationInRelationships.getOrDefault(conceptNotActive, Collections.emptySet())) {
				relationshipWithInactiveDestination.put(relationshipId, conceptNotActive);
			}
			for (String axiomId : conceptUsedInAxioms.getOrDefault(conceptNotActive, Collections.emptySet())) {
				axiomWithInactiveReferencedConcept.computeIfAbsent(axiomId, id -> new HashSet<>()).add(conceptNotActive);
			}
		}

		Map<String, ConceptMini> axiomsMinisAndInactiveConcepts = new HashMap<>();
		Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
		for (String axiomId : axiomWithInactiveReferencedConcept.keySet()) {
			addConceptMini(axiomsMinisAndInactiveConcepts, conceptMiniMap, axiomId, axiomIdReferenceComponentMap.get(axiomId), axiomWithInactiveReferencedConcept.get(axiomId));
		}
		descriptionService.joinActiveDescriptions(branch.getPath(), conceptMiniMap);

		timer.finish();

		return getReport(axiomsMinisAndInactiveConcepts, relationshipWithInactiveSource, relationshipWithInactiveType, relationshipWithInactiveDestination);
	}


	public IntegrityIssueReport findChangedComponentsWithBadIntegrity(Branch taskBranch, String extensionMainBranchPath) throws ServiceException {
		Branch extensionMain = branchService.findBranchOrThrow(extensionMainBranchPath);
		Branch projectBranch = branchService.findBranchOrThrow(PathUtil.getParentPath(taskBranch.getPath()));
		if (!projectBranch.getPath().equalsIgnoreCase(extensionMainBranchPath) && !PathUtil.getParentPath(projectBranch.getPath()).equalsIgnoreCase(extensionMain.getPath())) {
			throw new RuntimeServiceException(String.format("Branch %s is not a descendant of %s", projectBranch.getPath(), extensionMainBranchPath));
		}
		// make sure project and task are rebased
		if (!projectBranch.getPath().equalsIgnoreCase(extensionMain.getPath()) && projectBranch.getBaseTimestamp() < extensionMain.getHeadTimestamp()) {
			throw new RuntimeServiceException(String.format("Branch %s needs to rebase first before running integrity check", projectBranch.getPath()));
		}
		if (taskBranch.getBaseTimestamp() < extensionMain.getHeadTimestamp()) {
			throw new RuntimeServiceException(String.format("Branch %s needs to rebase first before running integrity check", taskBranch.getPath()));
		}

		TimerUtil timer = new TimerUtil("Changed component integrity check on " + taskBranch.getPath() + " and " + extensionMainBranchPath, Level.INFO, 1);
		IntegrityIssueReport integrityIssueReportOnExtensionMain = findChangedComponentsWithBadIntegrity(extensionMain);
		if (integrityIssueReportOnExtensionMain.isEmpty()) {
			logger.info("No integrity issue found on {}", extensionMainBranchPath);
			return findChangedComponentsWithBadIntegrity(taskBranch);
		}
		Map<Long, Long> relationshipWithInactiveSource = integrityIssueReportOnExtensionMain.getRelationshipsWithMissingOrInactiveSource();
		Map<Long, Long> relationshipWithInactiveType = integrityIssueReportOnExtensionMain.getRelationshipsWithMissingOrInactiveType();
		Map<Long, Long> relationshipWithInactiveDestination = integrityIssueReportOnExtensionMain.getRelationshipsWithMissingOrInactiveDestination();

		Set<Long> relationshipIdsWithBadIntegrity = new HashSet<>();
		if (relationshipWithInactiveSource != null) {
			logger.info("{} relationships with inactive source found on {}", relationshipWithInactiveSource.keySet().size(), extensionMainBranchPath);
			relationshipIdsWithBadIntegrity.addAll(relationshipWithInactiveSource.keySet());
		}
		if (relationshipWithInactiveType != null) {
			logger.info("{} relationships with inactive type found on {}", relationshipWithInactiveType.keySet().size(), extensionMainBranchPath);
			relationshipIdsWithBadIntegrity.addAll(relationshipWithInactiveType.keySet());
		}
		if (relationshipWithInactiveDestination != null) {
			logger.info("{} relationships with inactive destination found on {}", relationshipWithInactiveDestination.keySet().size(), extensionMainBranchPath);
			relationshipIdsWithBadIntegrity.addAll(relationshipWithInactiveDestination.keySet());
		}

		Map<String, ConceptMini> axiomsWithInactiveConcept = integrityIssueReportOnExtensionMain.getAxiomsWithMissingOrInactiveReferencedConcept();
		Set<String> axiomsWithBadIntegrity = axiomsWithInactiveConcept != null ? axiomsWithInactiveConcept.keySet() : new HashSet<>();
		logger.info("{} axioms referenced inactive concept found on {}", axiomsWithBadIntegrity.size(), extensionMainBranchPath);
		timer.checkpoint("Integrity check completed on " + extensionMainBranchPath);

		// fetch source, type and destination in the fix task for relationships reported
		BranchCriteria taskBranchCriteria = versionControlHelper.getBranchCriteria(taskBranch);
		Map<Long, Long> relationshipIdToSourceMap = new HashMap<>();
		Map<Long, Long> relationshipIdToTypeMap = new HashMap<>();
		Map<Long, Long> relationshipIdToDestinationMap = new HashMap<>();
		try (SearchHitsIterator<Relationship> badRelationshipsStream = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(taskBranchCriteria.getEntityBranchCriteria(Relationship.class))
								.must(termsQuery(Relationship.Fields.ACTIVE, true))
								.mustNot(termsQuery(Relationship.Fields.CHARACTERISTIC_TYPE_ID, Concepts.INFERRED_RELATIONSHIP))
								.must(termsQuery(Relationship.Fields.RELATIONSHIP_ID, relationshipIdsWithBadIntegrity))
						)
						.withPageable(LARGE_PAGE).build(),
				Relationship.class)) {
			badRelationshipsStream.forEachRemaining(hit -> {
				Relationship relationship = hit.getContent();
				relationshipIdToSourceMap.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getSourceId()));
				relationshipIdToTypeMap.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getTypeId()));
				relationshipIdToDestinationMap.put(parseLong(relationship.getRelationshipId()), parseLong(relationship.getDestinationId()));
			});
		}

		// fetch concepts referenced by axioms reported with bad integrity
		Map<Long, Set<String>> conceptUsedInAxioms = new Long2ObjectOpenHashMap<>();
		Map<String, String> axiomIdReferenceComponentMap = new HashMap<>();
		try (SearchHitsIterator<ReferenceSetMember> axiomStream = elasticsearchTemplate.searchForStream(new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(taskBranchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
								.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
								.must(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.OWL_AXIOM_REFERENCE_SET))
								.must(termsQuery(ReferenceSetMember.Fields.MEMBER_ID, axiomsWithBadIntegrity))
						)
						.withPageable(LARGE_PAGE)
						.build(),
				ReferenceSetMember.class)) {
			try {
				while (axiomStream.hasNext()) {
					ReferenceSetMember axiom = axiomStream.next().getContent();
					axiomIdReferenceComponentMap.put(axiom.getMemberId(), axiom.getReferencedComponentId());
					Set<Long> referencedConcepts = axiomConversionService.getReferencedConcepts(axiom.getAdditionalField(OWL_EXPRESSION));
					for (Long referencedConcept : referencedConcepts) {
						conceptUsedInAxioms.computeIfAbsent(referencedConcept, id -> new HashSet<>()).add(axiom.getId());
					}
				}
			} catch (ConversionException e) {
				throw new ServiceException("Failed to deserialise axiom during reference integrity check.", e);
			}
		}

		Set<Long> conceptIdsToCheck = new HashSet<>();
		conceptIdsToCheck.addAll(conceptUsedInAxioms.keySet());
		conceptIdsToCheck.addAll(relationshipIdToSourceMap.values());
		conceptIdsToCheck.addAll(relationshipIdToDestinationMap.values());
		conceptIdsToCheck.addAll(relationshipIdToTypeMap.values());

		Set<Long> activeConcepts = new LongOpenHashSet();
		try (SearchHitsIterator<Concept> activeConceptStream = elasticsearchTemplate.searchForStream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(taskBranchCriteria.getEntityBranchCriteria(Concept.class))
						.must(termQuery(Concept.Fields.ACTIVE, true))
						.must(termsQuery(Concept.Fields.CONCEPT_ID, conceptIdsToCheck))
				)
				.withFields(Concept.Fields.CONCEPT_ID)
				.build(), Concept.class)) {
			activeConceptStream.forEachRemaining(hit -> activeConcepts.add(hit.getContent().getConceptIdAsLong()));
		}
		timer.checkpoint("Collect active concepts referenced in changed relationships and axioms: " + activeConcepts.size() + " on " + taskBranch.getPath());

		// check axioms still with bad integrity
		Map<String, Set<Long>> axiomWithInactiveReferencedConcept = new HashMap<>();
		for (Long concept : conceptUsedInAxioms.keySet()) {
			if (!activeConcepts.contains(concept)) {
				for (String axiomId : conceptUsedInAxioms.get(concept)) {
					axiomWithInactiveReferencedConcept.computeIfAbsent(axiomId, id -> new HashSet<>()).add(concept);
				}
			}
		}
		logger.info("{} axioms still with referenced inactive concepts", axiomWithInactiveReferencedConcept.keySet().size());

		// check relationships still with bad integrity
		Map<Long, Long> relationshipStillWithInactiveSource = new HashMap<>();
		Map<Long, Long> relationshipStillWithInactiveType = new HashMap<>();
		Map<Long, Long> relationshipStillWithInactiveDestination = new HashMap<>();
		for (Long relationshipId : relationshipIdToSourceMap.keySet()) {
			if (!activeConcepts.contains(relationshipIdToSourceMap.get(relationshipId))) {
				relationshipStillWithInactiveSource.put(relationshipId, relationshipIdToSourceMap.get(relationshipId));
			}
		}

		for (Long relationshipId : relationshipIdToDestinationMap.keySet()) {
			if (!activeConcepts.contains(relationshipIdToDestinationMap.get(relationshipId))) {
				relationshipStillWithInactiveDestination.put(relationshipId, relationshipIdToDestinationMap.get(relationshipId));
			}
		}

		for (Long relationshipId : relationshipStillWithInactiveType.keySet()) {
			if (!activeConcepts.contains(relationshipStillWithInactiveType.get(relationshipId))) {
				relationshipStillWithInactiveType.put(relationshipId, relationshipStillWithInactiveType.get(relationshipId));
			}
		}

		Map<String, ConceptMini> axiomsMinisAndInactiveConcepts = new HashMap<>();
		Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
		for (String axiomId : axiomWithInactiveReferencedConcept.keySet()) {
			addConceptMini(axiomsMinisAndInactiveConcepts, conceptMiniMap, axiomId, axiomIdReferenceComponentMap.get(axiomId), axiomWithInactiveReferencedConcept.get(axiomId));
		}
		descriptionService.joinActiveDescriptions(taskBranch.getPath(), conceptMiniMap);

		timer.finish();
		IntegrityIssueReport fixedReport = getReport(axiomsMinisAndInactiveConcepts, relationshipStillWithInactiveSource, relationshipStillWithInactiveType, relationshipStillWithInactiveDestination);
		if (fixedReport.isEmpty()) {
			// update the internal flag to false on the fix branch
			taskBranch.getMetadata().getMapOrCreate(INTERNAL_METADATA_KEY).put(IntegrityService.INTEGRITY_ISSUE_METADATA_KEY, "false");
			branchService.updateMetadata(taskBranch.getPath(), taskBranch.getMetadata());
			logger.info("Integrity issues have been fixed on branch {}", taskBranch.getPath());
		}
		return fixedReport;
	}


	public IntegrityIssueReport findAllComponentsWithBadIntegrity(Branch branch, boolean stated) throws ServiceException {

		final Map<Long, Long> relationshipWithInactiveSource = new Long2LongOpenHashMap();
		final Map<Long, Long> relationshipWithInactiveType = new Long2LongOpenHashMap();
		final Map<Long, Long> relationshipWithInactiveDestination = new Long2LongOpenHashMap();
		final Map<String, ConceptMini> axiomWithInactiveReferencedConcept = new HashMap<>();

		BranchCriteria branchCriteria = versionControlHelper.getBranchCriteria(branch);
		TimerUtil timer = new TimerUtil("Full integrity check on " + branch.getPath());

		// Fetch all active concepts
		Set<Long> activeConcepts = new LongOpenHashSet(conceptService.findAllActiveConcepts(branchCriteria));
		timer.checkpoint("Fetch active concepts: " + activeConcepts.size());

		// Find relationships pointing to something other than the active concepts
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		BoolQueryBuilder boolQueryBuilder = boolQuery();
		queryBuilder
				.withQuery(boolQueryBuilder
						.must(branchCriteria.getEntityBranchCriteria(Relationship.class))
						.must(termQuery(Relationship.Fields.ACTIVE, true))
						.must(boolQuery()
							.should(boolQuery().mustNot(termsQuery(Relationship.Fields.SOURCE_ID, activeConcepts)))
							.should(boolQuery().mustNot(termsQuery(Relationship.Fields.TYPE_ID, activeConcepts)))
							.should(boolQuery().mustNot(termsQuery(Relationship.Fields.DESTINATION_ID, activeConcepts)))
						)
				)
				.withPageable(LARGE_PAGE);
		if (stated) {
			boolQueryBuilder.mustNot(termsQuery(Relationship.Fields.CHARACTERISTIC_TYPE_ID, Concepts.INFERRED_RELATIONSHIP));
		} else {
			boolQueryBuilder.must(termsQuery(Relationship.Fields.CHARACTERISTIC_TYPE_ID, Concepts.INFERRED_RELATIONSHIP));
		}
		try (SearchHitsIterator<Relationship> relationshipStream = elasticsearchTemplate.searchForStream(queryBuilder.build(), Relationship.class)) {
			relationshipStream.forEachRemaining(hit -> {
				Relationship relationship = hit.getContent();
				long relationshipId = parseLong(relationship.getRelationshipId());
				putIfInactive(relationship.getSourceId(), activeConcepts, relationshipId, relationshipWithInactiveSource);
				putIfInactive(relationship.getTypeId(), activeConcepts, relationshipId, relationshipWithInactiveType);
				putIfInactive(relationship.getDestinationId(), activeConcepts, relationshipId, relationshipWithInactiveDestination);
			});
		}

		// Find Axioms pointing to something other than the active concepts, use semantic index first.
		Set<Long> conceptIdsWithBadAxioms = new LongOpenHashSet();
		try (SearchHitsIterator<QueryConcept> badStatedIndexConcepts = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteria.getEntityBranchCriteria(QueryConcept.class))
								.must(termQuery(QueryConcept.Fields.STATED, true))
								.mustNot(termsQuery(QueryConcept.Fields.ATTR + "." + QueryConcept.ATTR_TYPE_WILDCARD, activeConcepts))
						)
						.withPageable(LARGE_PAGE).build(),
				QueryConcept.class)) {
			badStatedIndexConcepts.forEachRemaining(hit -> conceptIdsWithBadAxioms.add(hit.getContent().getConceptIdL()));
		}
		if (!conceptIdsWithBadAxioms.isEmpty()) {
			try (SearchHitsIterator<ReferenceSetMember> possiblyBadAxioms = elasticsearchTemplate.searchForStream(
					new NativeSearchQueryBuilder()
							.withQuery(boolQuery()
									.must(branchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
									.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
									.must(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.OWL_AXIOM_REFERENCE_SET))
									.must(termsQuery(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID, conceptIdsWithBadAxioms))
							)
							.withPageable(LARGE_PAGE).build(),
					ReferenceSetMember.class)) {
				try {
					Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
					while (possiblyBadAxioms.hasNext()) {
						ReferenceSetMember axiomMember = possiblyBadAxioms.next().getContent();
						String owlExpression = axiomMember.getAdditionalField(OWL_EXPRESSION);
						Set<Long> referencedConcepts = axiomConversionService.getReferencedConcepts(owlExpression);
						Sets.SetView<Long> badReferences = Sets.difference(referencedConcepts, activeConcepts);
						if (!badReferences.isEmpty()) {
							addConceptMini(axiomWithInactiveReferencedConcept, conceptMiniMap, axiomMember.getId(), axiomMember.getReferencedComponentId(), badReferences);
						}
					}
					// Join descriptions so FSN and PT are returned
					descriptionService.joinActiveDescriptions(branch.getPath(), conceptMiniMap);
				} catch (ConversionException e) {
					throw new ServiceException("Failed to deserialise axiom during reference integrity check.", e);
				}
			}
		}

		timer.finish();

		return getReport(axiomWithInactiveReferencedConcept, relationshipWithInactiveSource, relationshipWithInactiveType, relationshipWithInactiveDestination);
	}

	private void addConceptMini(Map<String, ConceptMini> axiomsWithInactiveReferencedConcept, Map<String, ConceptMini> conceptMiniMap,
			String axiomMemberId, String referencedComponentId, Collection<Long> badReferences) {

		ConceptMini conceptMini = axiomsWithInactiveReferencedConcept.computeIfAbsent(axiomMemberId, id ->
				conceptMiniMap.computeIfAbsent(referencedComponentId, conceptId -> new ConceptMini(conceptId, Config.DEFAULT_LANGUAGE_DIALECTS))
		);
		if (conceptMini.getExtraFields() == null) {
			conceptMini.setExtraFields(new HashMap<>());
		}
		@SuppressWarnings("unchecked")
		Set<Long> missingOrInactiveConcepts = (Set<Long>) conceptMini.getExtraFields().computeIfAbsent("missingOrInactiveConcepts", i -> new HashSet<Long>());
		missingOrInactiveConcepts.addAll(badReferences);
	}

	private IntegrityIssueReport getReport(Map<String, ConceptMini> axiomWithInactiveReferencedConcept, Map<Long, Long> relationshipWithInactiveSource, Map<Long, Long> relationshipWithInactiveType, Map<Long, Long> relationshipWithInactiveDestination) {

		IntegrityIssueReport issueReport = new IntegrityIssueReport();

		if (!axiomWithInactiveReferencedConcept.isEmpty()) {
			issueReport.setAxiomsWithMissingOrInactiveReferencedConcept(axiomWithInactiveReferencedConcept);
		}

		if (!relationshipWithInactiveSource.isEmpty()) {
			issueReport.setRelationshipsWithMissingOrInactiveSource(relationshipWithInactiveSource);
		}
		if (!relationshipWithInactiveType.isEmpty()) {
			issueReport.setRelationshipsWithMissingOrInactiveType(relationshipWithInactiveType);
		}
		if (!relationshipWithInactiveDestination.isEmpty()) {
			issueReport.setRelationshipsWithMissingOrInactiveDestination(relationshipWithInactiveDestination);
		}

		return issueReport;
	}

	private void putIfInactive(String sourceId, Collection<Long> activeConcepts, long relationshipId, Map<Long, Long> relationshipWithInactiveSource) {
		long source = parseLong(sourceId);
		if (!activeConcepts.contains(source)) {
			relationshipWithInactiveSource.put(relationshipId, source);
		}
	}

	public ConceptsInForm findExtraConceptsInSemanticIndex(String branchPath) {
		TimerUtil timer = new TimerUtil("Semantic delete check");
		BranchCriteria branchCriteria = versionControlHelper.getBranchCriteria(branchPath);

		Set<Long> activeConcepts = new LongOpenHashSet(conceptService.findAllActiveConcepts(branchCriteria));
		timer.checkpoint("Fetch active concepts: " + activeConcepts.size());

		List<Long> statedIds = new ArrayList<>();
		List<Long> inferredIds = new ArrayList<>();
		try (SearchHitsIterator<QueryConcept> stream = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery().must(branchCriteria.getEntityBranchCriteria(QueryConcept.class)))
						.withFilter(boolQuery().mustNot(termsQuery(QueryConcept.Fields.CONCEPT_ID, activeConcepts)))
						.withPageable(LARGE_PAGE)
						.build(), QueryConcept.class)) {
			stream.forEachRemaining(hit -> {
				QueryConcept semanticConcept = hit.getContent();
				if (semanticConcept.isStated()) {
					statedIds.add(semanticConcept.getConceptIdL());
				} else {
					inferredIds.add(semanticConcept.getConceptIdL());
				}
			});
		}
		timer.checkpoint("Check whole semantic index for branch.");
		timer.finish();

		if (!statedIds.isEmpty() || !inferredIds.isEmpty()) {
			logger.error("Found {} stated and {} inferred concepts in semantic index for branch {} which should not be there.", statedIds.size(), inferredIds.size(), branchPath);
		} else {
			logger.info("Found {} stated and {} inferred concepts in semantic index for branch {} which should not be there.", statedIds.size(), inferredIds.size(), branchPath);
		}

		return new ConceptsInForm(statedIds, inferredIds);
	}

	private Set<Long> findDeletedOrInactivatedConcepts(Branch branch, BranchCriteria branchCriteria) {
		// Find Concepts changed or deleted on this branch
		final Set<Long> changedOrDeletedConcepts = new LongOpenHashSet();
		try (SearchHitsIterator<Concept> changedOrDeletedConceptStream = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery().must(versionControlHelper.getBranchCriteriaUnpromotedChangesAndDeletions(branch).getEntityBranchCriteria(Concept.class)))
						.withFields(Concept.Fields.CONCEPT_ID)
						.withPageable(LARGE_PAGE).build(), Concept.class)) {
			changedOrDeletedConceptStream.forEachRemaining(hit -> changedOrDeletedConcepts.add(hit.getContent().getConceptIdAsLong()));
		}
		logger.info("Concepts changed or deleted on branch {} = {}", branch.getPath(), changedOrDeletedConcepts.size());

		// Of these concepts, which are currently present and active?
		final Set<Long> changedAndActiveConcepts = new LongOpenHashSet();
		try (SearchHitsIterator<Concept> changedOrDeletedConceptStream = elasticsearchTemplate.searchForStream(
				new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteria.getEntityBranchCriteria(Concept.class))
								.must(termsQuery(Concept.Fields.CONCEPT_ID, changedOrDeletedConcepts))
								.must(termQuery(Concept.Fields.ACTIVE, true))
						)
						.withFields(Concept.Fields.CONCEPT_ID)
						.withPageable(LARGE_PAGE).build(),
				Concept.class)) {
			changedOrDeletedConceptStream.forEachRemaining(hit -> changedAndActiveConcepts.add(hit.getContent().getConceptIdAsLong()));
		}
		logger.info("Concepts changed, currently present and active on branch {} = {}", branch.getPath(), changedAndActiveConcepts.size());

		// Therefore concepts deleted or inactive are:
		Set<Long> deletedOrInactiveConcepts = new LongOpenHashSet(changedOrDeletedConcepts);
		deletedOrInactiveConcepts.removeAll(changedAndActiveConcepts);
		logger.info("Concepts deleted or inactive on branch {} = {}", branch.getPath(), deletedOrInactiveConcepts.size());
		return deletedOrInactiveConcepts;
	}

	public static class ConceptsInForm {
		private List<Long> statedConceptIds;
		private List<Long> inferredConceptIds;

		public ConceptsInForm(List<Long> statedIds, List<Long> inferredIds) {
			this.statedConceptIds = statedIds;
			this.inferredConceptIds = inferredIds;
		}

		public List<Long> getStatedConceptIds() {
			return statedConceptIds;
		}

		public void setStatedConceptIds(List<Long> statedConceptIds) {
			this.statedConceptIds = statedConceptIds;
		}

		public List<Long> getInferredConceptIds() {
			return inferredConceptIds;
		}

		public void setInferredConceptIds(List<Long> inferredConceptIds) {
			this.inferredConceptIds = inferredConceptIds;
		}
	}
}

