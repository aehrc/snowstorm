package org.snomed.snowstorm.validation;

import joptsimple.internal.Strings;
import org.elasticsearch.index.query.QueryBuilder;
import org.ihtsdo.drools.domain.Relationship;
import org.ihtsdo.drools.exception.RuleExecutorException;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.services.QueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;

public class ConceptDroolsValidationService implements org.ihtsdo.drools.service.ConceptService {

	private final String branchPath;
	private final QueryBuilder branchCriteria;
	private final ElasticsearchOperations elasticsearchTemplate;
	private final QueryService queryService;

	ConceptDroolsValidationService(String branchPath, QueryBuilder branchCriteria, ElasticsearchOperations elasticsearchTemplate, QueryService queryService) {
		this.branchPath = branchPath;
		this.branchCriteria = branchCriteria;
		this.elasticsearchTemplate = elasticsearchTemplate;
		this.queryService = queryService;
	}

	@Override
	public boolean isActive(String conceptId) {
		NativeSearchQuery query = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termQuery("conceptId", conceptId)))
				.withPageable(Config.PAGE_OF_ONE)
				.build();
		List<Concept> matches = elasticsearchTemplate.queryForList(query, Concept.class);
		if (matches.isEmpty()) {
			throw new RuleExecutorException(String.format("Concept '%s' not found on branch '%s'", conceptId, branchPath));
		}
		return matches.get(0).isActive();
	}

	@Override
	public Set<String> getAllTopLevelHierachies() {
		return getConceptIdsByEcl(false, "<!" + Concepts.SNOMEDCT_ROOT);
	}

	@Override
	public Set<String> findStatedAncestorsOfConcept(org.ihtsdo.drools.domain.Concept concept) {
		// This could be an unsaved concept, don't use the concept id, collect the stated parents - they will have an SCTID.

		Set<String> statedParents = getStatedParents(concept);
		if (statedParents.isEmpty()) {
			return Collections.emptySet();
		}

		StringBuilder ecl = new StringBuilder();
		Iterator<String> iterator = statedParents.iterator();
		for (int i = 0; i < statedParents.size(); i++) {
			if (i > 0) {
				ecl.append(" OR ");
			}
			ecl.append(">>").append(iterator.next());// Include self because this ID is a parent.
		}

		return getConceptIdsByEcl(true, ecl.toString());
	}

	@Override
	public Set<String> findTopLevelHierachiesOfConcept(org.ihtsdo.drools.domain.Concept concept) {
		Set<String> statedParents = getStatedParents(concept);

		StringBuilder ecl = new StringBuilder("<!" + Concepts.SNOMEDCT_ROOT + " AND ");
		Iterator<String> iterator = statedParents.iterator();
		if (statedParents.size() > 1) {
			ecl.append("(");
		}
		for (int i = 0; i < statedParents.size(); i++) {
			if (i > 0) {
				ecl.append(" OR ");
			}
			ecl.append(">>").append(iterator.next());// Include self because this ID is a parent.
		}
		if (statedParents.size() > 1) {
			ecl.append(")");
		}

		return getConceptIdsByEcl(false, ecl.toString());
	}

	private Set<String> getStatedParents(org.ihtsdo.drools.domain.Concept concept) {
		return concept.getRelationships().stream()
				.filter(r -> Concepts.ISA.equals(r.getTypeId()))
				.map(Relationship::getTypeId)
				.collect(Collectors.toSet());
	}

	private Set<String> getConceptIdsByEcl(boolean stated, String ecl) {
		Page<ConceptMini> directDescendantsOfRoot = queryService.search(
				queryService.createQueryBuilder(stated).ecl(ecl),
				branchPath, PageRequest.of(0, 1000));
		return directDescendantsOfRoot.getContent().stream().map(ConceptMini::getConceptId).collect(Collectors.toSet());
	}
}
