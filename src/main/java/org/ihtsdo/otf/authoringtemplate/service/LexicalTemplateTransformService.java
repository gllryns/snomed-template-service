package org.ihtsdo.otf.authoringtemplate.service;

import org.apache.commons.lang.StringUtils;
import org.ihtsdo.otf.authoringtemplate.service.exception.ServiceException;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.DescriptionPojo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.authoringtemplate.domain.CaseSignificance;
import org.snomed.authoringtemplate.domain.Description;
import org.snomed.authoringtemplate.domain.DescriptionType;
import org.snomed.authoringtemplate.domain.LexicalTemplate;
import org.snomed.authoringtemplate.domain.LexicalTemplate.ReplacementRule;

import java.util.*;
import java.util.stream.Collectors;

import static org.snomed.authoringtemplate.domain.CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE;
import static org.snomed.authoringtemplate.domain.CaseSignificance.INITIAL_CHARACTER_CASE_INSENSITIVE;

public class LexicalTemplateTransformService {

	private static final String TERM_SLOT_INDICATOR = "$";
	private static final Logger LOGGER = LoggerFactory.getLogger(LexicalTemplateTransformService.class);

	public static List<Description> transformDescriptions(List<LexicalTemplate> lexicalTemplates,
			List<Description> descriptions, Map<String, Set<DescriptionPojo>> slotValueMap) throws ServiceException {
		
		Map<String, LexicalTemplate> lexicalTemplateMap = new HashMap<>();
		for (LexicalTemplate template : lexicalTemplates) {
			lexicalTemplateMap.put(template.getName(), template);
		}
		
		Map<String, List<DescriptionPojo>> slotFsnValueMap = new HashMap<>();
		for (String slot : slotValueMap.keySet()) {
			List<DescriptionPojo> fsnPojos = slotValueMap.get(slot).stream()
					.filter(DescriptionPojo::isActive)
					.filter(v -> DescriptionType.FSN.name().equals(v.getType()))
					.collect(Collectors.toList());
			slotFsnValueMap.put(slot, fsnPojos);
		}
		Map<String, List<DescriptionPojo>> slotPtValueMap = new HashMap<>();
		for (String slot : slotValueMap.keySet()) {
			List<DescriptionPojo> ptPojos = slotValueMap.get(slot).stream()
					.filter(DescriptionPojo::isActive)
					.filter(v -> DescriptionType.SYNONYM.name().equals(v.getType()))
					.filter(v -> v.getAcceptabilityMap().values().contains(Constants.PREFERRED))
					.collect(Collectors.toList());
			slotPtValueMap.put(slot, ptPojos);
		}
		return performTransformation(descriptions, slotFsnValueMap, slotPtValueMap, lexicalTemplateMap);
	}
	
	private static List<Description> performTransformation(List<Description> descriptions,
			Map<String, List<DescriptionPojo>> slotFsnValuesMap,
			Map<String, List<DescriptionPojo>> slotPtValueMap,
			Map<String, LexicalTemplate> lexicalTemplateMap) throws ServiceException {
		
		List<Description> updated = new ArrayList<>();
		List<Description> pts = descriptions.stream()
				.filter(d -> DescriptionType.SYNONYM == d.getType())
				.filter(d -> d.getAcceptabilityMap() != null)
				.filter(d -> d.getAcceptabilityMap().values().contains(Constants.PREFERRED))
				.collect(Collectors.toList());
		
		List<Description> others = new ArrayList<>(descriptions);
		others.removeAll(pts);
		for (Description description : others) {
			Description toTransform = description.clone();
			String term = toTransform.getTermTemplate();
			Set<String> termSlotNames = TemplateUtil.getSlots(Collections.singleton(term));
			Map<String, String> termAndCaseSignificanceMap = new HashMap<>();
			for (String slotName : termSlotNames) {
				LexicalTemplate template = lexicalTemplateMap.get(slotName);
				String termSlot = TERM_SLOT_INDICATOR + slotName + TERM_SLOT_INDICATOR;
				DescriptionPojo fsnPojo;
				if (template == null) {
					// Additional slot
					fsnPojo = slotFsnValuesMap.get(slotName).get(0); 
					String slotValue = TemplateUtil.getDescriptionFromFSN(fsnPojo);
					term = term.replace(termSlot, slotValue);
					termAndCaseSignificanceMap.put(slotValue, fsnPojo.getCaseSignificance());
				} else {
					term = applyFsnTransformation(term, template, slotFsnValuesMap, termAndCaseSignificanceMap, termSlot);
				}
			}
			updateFinalCaseSignificanceId(term, termAndCaseSignificanceMap, toTransform);
			updated.add(toTransform);
		}
		updated.addAll(transformPreferredTerms(pts, slotFsnValuesMap, slotPtValueMap, lexicalTemplateMap));
		return updated;
	}

	private static List<Description> transformPreferredTerms(List<Description> pts,
			Map<String, List<DescriptionPojo>> slotFsnValueMap, Map<String, List<DescriptionPojo>> slotPtValueMap, Map<String, LexicalTemplate> lexicalTemplateMap) {

		List<Description> result = new ArrayList<>();
		for (Description description : pts) {
			List<Description> temp = new ArrayList<>();
			for (String refsetId : description.getAcceptabilityMap().keySet()) {
				Description pt = description.clone();
				Map<String, String> acceptabilityMap = new HashMap<>();
				acceptabilityMap.put(refsetId, Constants.PREFERRED);
				pt.setAcceptabilityMap(acceptabilityMap);
				String term = pt.getTermTemplate();
				Set<String> termSlotNames = TemplateUtil.getSlots(Collections.singleton(term));
				Map<String, String> termAndCaseSignificanceMap = new HashMap<>();
				for (String slotName : termSlotNames) {
					LexicalTemplate template = lexicalTemplateMap.get(slotName);
					String termSlot = TERM_SLOT_INDICATOR + slotName + TERM_SLOT_INDICATOR;
					DescriptionPojo fsnPojo;
					if (template == null) {
						// Additional slot
						fsnPojo = slotFsnValueMap.get(slotName).get(0); 
						String slotValue = TemplateUtil.getDescriptionFromFSN(fsnPojo);
						term = term.replace(termSlot, slotValue);
						termAndCaseSignificanceMap.put(slotValue, fsnPojo.getCaseSignificance());
					} else {
						term = applyPreferredTermTransformation(term, template, slotPtValueMap, refsetId, termAndCaseSignificanceMap, termSlot);
					}
				}
				updateFinalCaseSignificanceId(term, termAndCaseSignificanceMap, pt);
				temp.add(pt);
			}
			// Check and merge if the term is same
			Set<String> ptTerms = temp.stream().map(Description::getTerm).collect(Collectors.toSet());
			if (ptTerms.size() == 1) {
				Map<String, String> acceptabilityMap = new HashMap<>();
				for (String key : description.getAcceptabilityMap().keySet()) {
					acceptabilityMap.put(key, Constants.PREFERRED);
				}
				Description mergedPt = temp.get(0);
				mergedPt.setAcceptabilityMap(acceptabilityMap);
				result.add(mergedPt);
			} else {
				result.addAll(temp);
			}
		}
		return result;
	}

	private static String applyPreferredTermTransformation(String term, LexicalTemplate template,
			Map<String, List<DescriptionPojo>> slotPtValueMap, String refsetId, Map<String, String> termAndCaseSignificanceMap, String termSlot) {
		
		List<DescriptionPojo> ptPojos = new ArrayList<>();
		if (slotPtValueMap.containsKey(template.getTakeFSNFromSlot())) {
			for (DescriptionPojo pojo : slotPtValueMap.get(template.getTakeFSNFromSlot())) {
				if (pojo.getAcceptabilityMap().keySet().contains(refsetId)) {
					if (Constants.PREFERRED.equals(pojo.getAcceptabilityMap().get(refsetId))) {
						ptPojos.add(pojo);
					}
				}
			}
		}
		if (ptPojos.isEmpty()) {
			term = performTermReplacementWhenSlotIsAbsent(term, template);
		} else {
			term = transformTerm(ptPojos, term, template, termAndCaseSignificanceMap, termSlot, false);
		}
		return term;
	}

	private static void updateFinalCaseSignificanceId(String term, Map<String, String> termAndCaseSignificanceMap, Description description) {
		// Remove extra spaces between words
		term = term.replaceAll("\\s+"," ").trim();
		Set<String> slotValues = new HashSet<>(termAndCaseSignificanceMap.keySet());
		for (String value : slotValues) {
			if (term.startsWith(value) && ENTIRE_TERM_CASE_SENSITIVE.name().equals(termAndCaseSignificanceMap.get(value))) {
				description.setCaseSignificance(ENTIRE_TERM_CASE_SENSITIVE);
				break;
			}
		}
		if (ENTIRE_TERM_CASE_SENSITIVE != description.getCaseSignificance()) {
			term = StringUtils.capitalize(term);
			if (termAndCaseSignificanceMap.values().contains(ENTIRE_TERM_CASE_SENSITIVE.name())
					|| termAndCaseSignificanceMap.values().contains(INITIAL_CHARACTER_CASE_INSENSITIVE.name())) {
				description.setCaseSignificance(INITIAL_CHARACTER_CASE_INSENSITIVE);
			}
		} 
		description.setTerm(term);
	}

	private static String applyFsnTransformation(String term, LexicalTemplate template,
			Map<String, List<DescriptionPojo>> slotFsnValueMap, Map<String, String> termAndCaseSignificanceMap, String termSlot) {

		List<DescriptionPojo> fsnPojos = slotFsnValueMap.get(template.getTakeFSNFromSlot());
		if (fsnPojos == null) {
			term = performTermReplacementWhenSlotIsAbsent(term, template);
		} else {
			term = transformTerm(fsnPojos, term, template, termAndCaseSignificanceMap, termSlot, true);
		}
		return term;
	}

	private static boolean isAdditionalTermReplacementRequired(LexicalTemplate template, String conceptId) {
		if (template.getTermReplacements() != null && !template.getTermReplacements().isEmpty()) {
			for (ReplacementRule rule : template.getTermReplacements()) {
				if (rule.getSlotValues() != null) {
					if (rule.getSlotValues().contains(conceptId)) {
						return true;
					}
				}
			}
		} 
		return false;
	}

	private static String performTermReplacementWithSlotValuesMatched(String term, LexicalTemplate template) {
		String result = term;
		if (template.getTermReplacements() != null) {
			for (ReplacementRule rule : template.getTermReplacements()) {
				if (rule.getSlotValues() != null) {
					result = result.replace(rule.getExistingTerm(), rule.getReplacement());
					LOGGER.debug(term + " is replaced by " + result);
					break;
				}
			}
		} 
		return result;
	}

	private static String performTermReplacementWhenSlotIsAbsent(String term, LexicalTemplate template) {
		String result = term;
		if (template.getTermReplacements() == null || template.getTermReplacements().isEmpty()) {
			// Perform default replacement
			result = result.replace(TERM_SLOT_INDICATOR + template.getName() + TERM_SLOT_INDICATOR, "");
		} else {
			for (ReplacementRule rule : template.getTermReplacements()) {
				if (rule.isSlotAbsent()) {
					result = result.replace(rule.getExistingTerm(), rule.getReplacement());
					break;
				}
			}
		}
		LOGGER.debug("{} is replaced due to absent slot by {}", term, result);
		return result;
	}
	
	private static String transformTerm(List<DescriptionPojo> descriptions, String term, LexicalTemplate template,
			Map<String, String> termAndCaseSignificanceMap, String termSlot, boolean isFsn) {
		// TODO need to improve here to deal with different type of transformations for multiple slot values for a given slot name
		// default for now to use "and". Ideally it should use regex
		List<String> slotValues = new ArrayList<>();
		for (DescriptionPojo pojo: descriptions) {
			if (isAdditionalTermReplacementRequired(template, pojo.getConceptId())) {
				term = performTermReplacementWithSlotValuesMatched(term, template);
			} else {
				String slotValue = isFsn ? TemplateUtil.getDescriptionFromFSN(pojo) : TemplateUtil.getDescriptionFromPT(pojo);
				if (template.getRemoveParts() != null && !template.getRemoveParts().isEmpty()) {
					for (String partToRemove : template.getRemoveParts()) {
						slotValue = slotValue.replaceAll(partToRemove, "");
					}
					if (CaseSignificance.CASE_INSENSITIVE.name().equals(pojo.getCaseSignificance())) {
						slotValue = StringUtils.uncapitalize(slotValue);
					}
				}
				slotValues.add(slotValue);
				termAndCaseSignificanceMap.put(slotValue, pojo.getCaseSignificance());
			}
		}
		Collections.sort(slotValues);
		StringBuilder termBuilder = new StringBuilder();
		for (int i=0; i < slotValues.size(); i++) {
			if (i > 0) {
				termBuilder.append(" and ");
			}
			termBuilder.append(slotValues.get(i));
		}
		
		return term.replace(termSlot, termBuilder.toString());
	}
}
