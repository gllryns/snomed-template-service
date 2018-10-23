package org.ihtsdo.otf.authoringtemplate.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.ihtsdo.otf.authoringtemplate.service.exception.ServiceException;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClientFactory;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ConceptMiniPojo;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ConceptPojo;
import org.ihtsdo.otf.rest.client.snowowl.pojo.DescriptionPojo;
import org.ihtsdo.otf.rest.client.snowowl.pojo.RelationshipPojo;
import org.ihtsdo.otf.rest.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.Description;
import org.snomed.authoringtemplate.domain.DescriptionType;
import org.snomed.authoringtemplate.domain.logical.Attribute;
import org.snomed.authoringtemplate.domain.logical.AttributeGroup;
import org.snomed.authoringtemplate.domain.logical.LogicalTemplate;
import org.snomed.authoringtemplate.service.LogicalTemplateParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TemplateConceptSearchService {

	private static final String AND = "AND";

	private static final String OR = "OR";

	private static final String CARDINALITY_SEPARATOR = "..";

	@Autowired 
	private LogicalTemplateParserService logicalTemplateParser;
	
	@Autowired
	private SnowOwlRestClientFactory terminologyClientFactory;
	
	@Autowired
	private TemplateService templateService;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TemplateConceptSearchService.class);

	private static final int MAX = 200000;
	
	public Set<String> searchConceptsByTemplate(String templateName, String branchPath, 
			TemplateSearchRequest searchRequest) throws ServiceException, ResourceNotFoundException {
		
			Boolean logicalMatch = searchRequest.isLogicalMatch();
			Boolean lexicalMatch = searchRequest.isLexicalMatch();
			LOGGER.info("Search concepts for template={}, on branchPath={}, with logigicalMatch={}, lexicalMatch={} and stated={}",
					templateName, branchPath, logicalMatch, lexicalMatch, searchRequest.isStated());
			
			if (logicalMatch == null) {
				throw new IllegalArgumentException("logicalMatch parameter must be specified.");
			}
			
			if (lexicalMatch != null) {
				if (!logicalMatch.booleanValue()) {
					throw new IllegalArgumentException("logicalMatch parameter must be true when lexicalMatch is set.");
				}
			}
			try {
				ConceptTemplate conceptTemplate = templateService.loadOrThrow(templateName);
				//parse logical
				LogicalTemplate logical = logicalTemplateParser.parseTemplate(conceptTemplate.getLogicalTemplate());
				Set<ConceptPojo> result = new HashSet<>();
				if (lexicalMatch != null) {
					Set<ConceptPojo> logicalResult = performLogicalSearch(conceptTemplate, logical, branchPath, true, searchRequest.isStated());
					result = performLexicalSearch(conceptTemplate, logical, logicalResult, branchPath, lexicalMatch, searchRequest.isPreciseLexicalMatch());
				} else {
					result = performLogicalSearch(conceptTemplate, logical, branchPath, logicalMatch, searchRequest.isStated());
				}
				return result.stream().map(c -> c.getConceptId()).collect(Collectors.toSet());
				
		} catch (IOException e) {
			throw new ServiceException("Failed to load tempate " + templateName);
		}
	}
	
	private Set<ConceptPojo> performLexicalSearch(ConceptTemplate conceptTemplate, LogicalTemplate logical, 
			Set<ConceptPojo> logicalMatched, String branchPath, boolean lexicalMatch, boolean isPreciseMatch) throws ServiceException {
		
		Set<ConceptPojo> result = new HashSet<>();
		if (logicalMatched == null || logicalMatched.isEmpty()) {
			LOGGER.info("No results found for logical search.");
			return result;
		}
		
		Map<Pattern, Set<String>> fsnPatternSlotsMap = TemplateUtil.compilePatterns(
				TemplateUtil.getTermTemplates(conceptTemplate, DescriptionType.FSN));
		Map<Pattern, Set<String>> synonymPatternSlotsMap = TemplateUtil.compilePatterns(
				TemplateUtil.getTermTemplates(conceptTemplate, DescriptionType.SYNONYM));
		
		for (ConceptPojo conceptPojo : logicalMatched) {
			boolean isMatched = matchByPattern(conceptPojo, fsnPatternSlotsMap, synonymPatternSlotsMap);
			if (isMatched && isPreciseMatch) {
				isMatched = peformPreciseMatch(conceptTemplate, logical, branchPath, conceptPojo);
			}
			if (lexicalMatch && isMatched) {
				result.add(conceptPojo);
			} else if (!lexicalMatch && !isMatched){
				result.add(conceptPojo);
			}
		}
		LOGGER.info("Logical search results={} and lexical search results={}", logicalMatched.size(), result.size());
		return result;
	}

	
	private boolean peformPreciseMatch(ConceptTemplate conceptTemplate, LogicalTemplate logical, String branchPath, ConceptPojo conceptPojo) throws ServiceException {
		// precise matching against slot values
		List<Description> descriptions = conceptTemplate.getConceptOutline().getDescriptions();
		Map<String, Set<String>> attributeSlots = TemplateUtil.getAttributeTypeSlotMap(logical);
		Map<String, ConceptMiniPojo> attributeSlotMap = TemplateUtil.getAttributeSlotValueMap(attributeSlots, conceptPojo);
		Map<String, Set<DescriptionPojo>> slotValueMap = TemplateUtil.getSlotDescriptionValuesMap(branchPath, attributeSlotMap, terminologyClientFactory.getClient());
		List<Description> expected = LexicalTemplateTransformService.transformDescriptions(conceptTemplate.getLexicalTemplates(), descriptions, slotValueMap);
		List<Description> expectedFsns = expected.stream()
				.filter(d -> DescriptionType.FSN.equals(d.getType()))
				.collect(Collectors.toList());
		
		List<String> synoyms = conceptPojo.getDescriptions()
				.stream()
				.filter(d->d.isActive())
				.filter(d -> d.getType().equals(DescriptionType.SYNONYM.name()))
				.map(d -> d.getTerm())
				.collect(Collectors.toList());

		List<String> fsns = conceptPojo.getDescriptions()
				.stream()
				.filter(d->d.isActive())
				.filter(d -> d.getType().equals(DescriptionType.FSN.name()))
				.map(d -> d.getTerm())
				.collect(Collectors.toList());
		
		for (Description fsn : expectedFsns) {
			if (!fsns.contains(fsn.getTerm())) {
				return false;
			} 
		}

		List<Description> expectedSynonyms = expected.stream()
				.filter(d -> DescriptionType.SYNONYM.equals(d.getType()))
				.collect(Collectors.toList());
		for (Description synoym : expectedSynonyms) {
			if (!synoyms.contains(synoym.getTerm())) {
				return false;
			} 
		}
		return true;
	}

	private boolean matchByPattern(ConceptPojo conceptPojo, Map<Pattern, Set<String>> fsnPatternSlotsMap, 
			Map<Pattern, Set<String>> synonymsPatternSlotsMap) {
		List<String> synonyms = conceptPojo.getDescriptions()
				.stream()
				.filter(d->d.isActive())
				.filter(d -> d.getType().equals(DescriptionType.SYNONYM.name()))
				.map(d -> d.getTerm())
				.collect(Collectors.toList());

		List<String> fsns = conceptPojo.getDescriptions()
				.stream()
				.filter(d->d.isActive())
				.filter(d -> d.getType().equals(DescriptionType.FSN.name()))
				.map(d -> d.getTerm())
				.collect(Collectors.toList());
		
		//matching FSN
		for (Pattern pattern : fsnPatternSlotsMap.keySet()) {
			if (!isPatternMatched(pattern, fsns)) {
				return false;
			}
		}
		//matching synonyms
		for (Pattern pattern : synonymsPatternSlotsMap.keySet()) {
			if (!isPatternMatched(pattern, synonyms)) {
				return false;
			}
		}
		return true;
	}
	
	private boolean isPatternMatched(Pattern pattern, Collection<String> terms) {
		for (String term : terms) {
			if (pattern.matcher(term).matches()) {
				return true;
			}
		}
		return false;
	}

	private Set<ConceptPojo> performLogicalSearch(ConceptTemplate conceptTemplate, LogicalTemplate logical,
			String branchPath, boolean logicalMatch, boolean stated) throws ServiceException {
		try {
			List<String> focusConcepts = logical.getFocusConcepts();
			String domainEcl = constructEclQuery(focusConcepts, Collections.emptyList(), Collections.emptyList());
			LOGGER.debug("Domain ECL=" + domainEcl);
			List<AttributeGroup> attributeGroups = logical.getAttributeGroups();
			List<Attribute> unGroupedAttributes = logical.getUngroupedAttributes();
			String logicalEcl = constructEclQuery(focusConcepts, attributeGroups, unGroupedAttributes);
			LOGGER.debug("Logic template ECL=" + logicalEcl);
			String ecl = constructLogicalSearchEcl(domainEcl, logicalEcl, logicalMatch);
			LOGGER.info("Logical search ECL={} stated={}", ecl, stated);
			Set<String> results = terminologyClientFactory.getClient().eclQuery(branchPath, ecl, MAX, stated);
			Set<ConceptPojo> conceptPojos = new HashSet<>(terminologyClientFactory.getClient().searchConcepts(branchPath, new ArrayList<String>(results)));
			Set<ConceptPojo> toRemove = findConceptsWithExtraAttributeTypes(conceptPojos, attributeGroups, unGroupedAttributes, stated);
			if (toRemove.size() > 0) {
				conceptPojos.removeAll(toRemove);
				LOGGER.info("Total concepts " + toRemove.size() + " with additional attributes and are removed from results " 
				+ toRemove.stream().map(r -> r.getConceptId()).collect(Collectors.toList()));
			}
			LOGGER.info("Logical results {}", conceptPojos.size());
			return conceptPojos;
		} catch (Exception e) {
			String msg = "Failed to complete logical template search for template " + conceptTemplate.getName();
			if (e.getCause() != null && e.getCause().getMessage() != null) {
				msg = msg + " due to " +  e.getCause().getMessage();
			}
			throw new ServiceException(msg, e);
		}
	}

	private Set<ConceptPojo> findConceptsWithExtraAttributeTypes(Set<ConceptPojo> conceptPojos, List<AttributeGroup> attributeGroups,
			List<Attribute> unGroupedAttributes, boolean stated) {
		
		List<Set<String>> allTypes = new ArrayList<>();
		List<Set<String>> mandatoryTypes = new ArrayList<>();
		for (AttributeGroup group : attributeGroups) {
			Set<String> groupTypes = new HashSet<>();
			Set<String> mandatoryGroupTypes = new HashSet<>();
			for (Attribute attribute : group.getAttributes()) {
				groupTypes.add(attribute.getType());
				if ("1".equals(attribute.getCardinalityMin())) {
					mandatoryGroupTypes.add(attribute.getType());
				}
			}
			allTypes.add(groupTypes);
			if ("1".equals(group.getCardinalityMin())) {
				mandatoryTypes.add(mandatoryGroupTypes);
			}
		}

		Set<String> ungrouped = new HashSet<>();
		Set<String> mandatoryUngrouped = new HashSet<>();
		ungrouped.add(Constants.IS_A);
		mandatoryUngrouped.add(Constants.IS_A);
		for (Attribute attr : unGroupedAttributes) {
			ungrouped.add(attr.getType());
			if ("1".equals(attr.getCardinalityMin())) {
				mandatoryUngrouped.add(attr.getType());
			}
		}
		allTypes.add(ungrouped);
		mandatoryTypes.add(mandatoryUngrouped);
		
		Set<ConceptPojo> results = new HashSet<>();
		for (ConceptPojo concept : conceptPojos) {
			//map relationship by group
			List<RelationshipPojo> activeRelationships = null;
			if (stated) {
				activeRelationships = concept.getRelationships().stream()
					.filter(r -> r.isActive())
					.filter(r -> r.getCharacteristicType().equals(Constants.STATED))
					.collect(Collectors.toList());
			} else {
				activeRelationships = concept.getRelationships().stream()
						.filter(r -> r.isActive())
						.filter(r -> r.getCharacteristicType().equals(Constants.INFERRED))
						.collect(Collectors.toList());
			}

			Map<Integer, Set<String>> relGroupMap = new HashMap<>();
			for (RelationshipPojo pojo : activeRelationships) {
				relGroupMap.computeIfAbsent(pojo.getGroupId(), k -> new HashSet<>())
				.add(pojo.getType().getConceptId());
			}
			if (containExtraAttribute(mandatoryTypes, allTypes, relGroupMap.values())) {
				results.add(concept);
			}
		}
		return results;
	}
	
	private boolean containExtraAttribute(List<Set<String>> mandatoryTypes, List<Set<String>> allTypes, Collection<Set<String>> relGroups) {
		for (Set<String> mandatory : mandatoryTypes) {
			boolean isFound = false;
			for (Set<String> typeSet : relGroups) {
				if (typeSet.containsAll(mandatory)) {
					isFound = true;
					break;
				}
			}
			if (!isFound) {
				return true;
			}
		}
		
		for (Set<String> typeSet : relGroups) {
			boolean isFound = false;
			for (Set<String> allType : allTypes) {
				if (allType.containsAll(typeSet)) {
					isFound = true;
					break;
				}
			}
			if (!isFound) {
				return true;
			}
		}
		return false;
	}

	private String constructLogicalSearchEcl(String domainEcl, String logicalEcl, boolean logicalMatch) {
		StringBuilder builder = new StringBuilder();
		builder.append("(");
		builder.append(domainEcl);
		builder.append(")");
		if (logicalMatch) {
			builder.append(" AND ");
		} else {
			builder.append(" MINUS ");
		}
		builder.append("(");
		builder.append(logicalEcl);
		builder.append(")");
		return builder.toString();
	}

	private Map<String,String> mapSlots(List<Attribute> attributes) {
		Map<String, String> slotMap = new HashMap<>();
		for (Attribute attribute : attributes) {
			if (attribute.getSlotName() != null) {
				String slotValue = null;
				if (attribute.getValue() != null) {
					slotValue = attribute.getValue();
				} else if (attribute.getAllowableRangeECL() != null) {
					slotValue = attribute.getAllowableRangeECL();
				}
				slotMap.put(attribute.getSlotName(), slotValue);
			}
		}
		return slotMap;
	}
	
	private String replaceSlot(String ecl, List<Attribute> attributes) {
		Map<String, String> slotMap = mapSlots(attributes);
		String result = ecl;
		for (String name : slotMap.keySet()) {
			result = result.replaceAll("=" + name, "=" + slotMap.get(name));
		}
		return result;
	}

	private String convertAttributeToEcl(List<Attribute> attributes) {
		StringBuilder queryBuilder = new StringBuilder();
		int counter = 0;
		for (Attribute attribute : attributes) {
			if (counter++ > 0) {
				queryBuilder.append(",");
			}
			if (attribute.getCardinalityMin() != null) {
				queryBuilder.append("[" + attribute.getCardinalityMin() + CARDINALITY_SEPARATOR);
			}
			if (attribute.getCardinalityMax() != null) {
				queryBuilder.append(attribute.getCardinalityMax() + "]");
			}
			queryBuilder.append(attribute.getType());
			queryBuilder.append( "=");
			if (attribute.getValue() != null) {
				queryBuilder.append(attribute.getValue());
			}
			else if (attribute.getAllowableRangeECL() != null) {
				boolean isCompound = false;
				if (attribute.getAllowableRangeECL().contains(OR) || attribute.getAllowableRangeECL().contains(AND)) {
					isCompound = true;
					queryBuilder.append("(");
				}
				queryBuilder.append(attribute.getAllowableRangeECL());
				if (isCompound) {
					queryBuilder.append(")");
				}
			} else if (attribute.getSlotReference() != null) {
				queryBuilder.append(attribute.getSlotReference());
			}
		}
		return queryBuilder.toString();
	}

	public String constructEclQuery(List<String> focusConcepts, List<AttributeGroup> attributeGroups,
			List<Attribute> unGroupedAttriburtes) throws ServiceException {
		List<Attribute> attributes = new ArrayList<>();
		attributes.addAll(unGroupedAttriburtes);
		StringBuilder queryBuilder = new StringBuilder();
		if (focusConcepts == null || focusConcepts.isEmpty()) {
			throw new ServiceException("No focus concepts defined!");
		}
		queryBuilder.append("<<" + focusConcepts.get(0));
		if (!attributeGroups.isEmpty() || !unGroupedAttriburtes.isEmpty()) {
			queryBuilder.append(":");
		}
		queryBuilder.append(convertAttributeToEcl(unGroupedAttriburtes));
		if (!unGroupedAttriburtes.isEmpty() && !attributeGroups.isEmpty()) {
			queryBuilder.append(",");
		}
		int groupCounter = 0;
		for (AttributeGroup group : attributeGroups) {
			attributes.addAll(group.getAttributes());
			if (groupCounter++ > 0) {
				queryBuilder.append(",");
			}
			if (group.getCardinalityMin() != null) {
				queryBuilder.append("[" + group.getCardinalityMin() + CARDINALITY_SEPARATOR);
			}
			if (group.getCardinalityMax() != null) {
				queryBuilder.append(group.getCardinalityMax() + "]");
			}
			queryBuilder.append("{");
			queryBuilder.append(convertAttributeToEcl(group.getAttributes()));
			queryBuilder.append("}");
		}
		 queryBuilder.toString();
		 return replaceSlot( queryBuilder.toString(), attributes);
	}
}
