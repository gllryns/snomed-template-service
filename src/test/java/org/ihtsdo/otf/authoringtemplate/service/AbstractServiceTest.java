package org.ihtsdo.otf.authoringtemplate.service;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.util.Lists;
import org.ihtsdo.otf.authoringtemplate.Config;
import org.ihtsdo.otf.authoringtemplate.TestConfig;
import org.ihtsdo.otf.authoringtemplate.service.exception.ServiceException;
import org.ihtsdo.otf.authoringtemplate.transform.TestDataHelper;
import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClient;
import org.ihtsdo.otf.rest.client.snowowl.SnowOwlRestClientFactory;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ConceptMiniPojo;
import org.ihtsdo.otf.rest.client.snowowl.pojo.ConceptPojo;
import org.ihtsdo.otf.rest.client.snowowl.pojo.DefinitionStatus;
import org.ihtsdo.otf.rest.client.snowowl.pojo.DescriptionPojo;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.snomed.authoringtemplate.domain.CaseSignificance;
import org.snomed.authoringtemplate.domain.ConceptOutline;
import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.Description;
import org.snomed.authoringtemplate.domain.DescriptionType;
import org.snomed.authoringtemplate.domain.LexicalTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.util.FileSystemUtils;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {Config.class, TestConfig.class})
public abstract class AbstractServiceTest {

	@Autowired
	protected TemplateService templateService;

	@Autowired
	protected TemplateStore templateStore;

	@MockBean
	protected SnowOwlRestClientFactory clientFactory;

	@MockBean
	protected SnowOwlRestClient terminologyServerClient;
	
	@Before
	public void before() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("", ""));
		templateStore.clear();
	}

	@After
	public void after() {
		// Recreate empty template store
		FileSystemUtils.deleteRecursively(templateStore.getJsonStore().getStoreDirectory());
		templateStore.getJsonStore().getStoreDirectory().mkdirs();
	}
	
	public void createCtGuidedProcedureOfX() throws IOException, ServiceException {
		final ConceptTemplate templateRequest = new ConceptTemplate();
		templateRequest.setDomain("<<71388002 |Procedure|");
		templateRequest.setLogicalTemplate("71388002 |Procedure|:   [[~1..1]] {      260686004 |Method| = 312251004 |Computed tomography imaging action|,      [[~1..1]] 405813007 |Procedure site - Direct| = [[+id(<< 442083009 |Anatomical or acquired body structure|) @procSite]],      363703001 |Has intent| = 429892002 |Guidance intent|   },   {      260686004 |Method| = [[+id (<< 129264002 |Action|) @action]],      [[~1..1]] 405813007 |Procedure site - Direct| = [[+id $procSite]]   }");

		templateRequest.addLexicalTemplate(new LexicalTemplate("procSiteTerm", "X", "procSite", Lists.newArrayList("structure of", "structure", "part of")));
		templateRequest.addLexicalTemplate(new LexicalTemplate("actionTerm", "Procedure", "action", Lists.newArrayList(" - action")));
		Description fsn = new Description("$actionTerm$ of $procSiteTerm$ using computed tomography guidance (procedure)");
		fsn.setType(DescriptionType.FSN);
		fsn.setAcceptabilityMap(TestDataHelper.constructAcceptabilityMap(Constants.PREFERRED, Constants.PREFERRED));
		Description pt = new Description("$actionTerm$ of $procSiteTerm$ using computed tomography guidance");
		pt.setType(DescriptionType.SYNONYM);
		pt.setAcceptabilityMap(TestDataHelper.constructAcceptabilityMap(Constants.PREFERRED, Constants.PREFERRED));
		templateRequest.setConceptOutline(new ConceptOutline().addDescription(fsn).addDescription(pt));
		templateService.create("CT Guided Procedure of X", templateRequest);
	}
	
	@SuppressWarnings("unchecked")
	public void mockSearchConcepts(ConceptPojo conceptToTransform) throws RestClientException {
		
		List<ConceptPojo> concepts = new ArrayList<>();
		Set<ConceptMiniPojo> targets = conceptToTransform.getRelationships().stream().filter(r -> r.isActive()).map(r -> r.getTarget()).collect(Collectors.toSet());
		for (ConceptMiniPojo targetPojo : targets) {
			concepts.add(constructConceptPojo(targetPojo));
		}
		when(terminologyServerClient.searchConcepts(anyString(),any()))
		.thenReturn(Arrays.asList(conceptToTransform), concepts);
	}
	
	private ConceptPojo constructConceptPojo(ConceptMiniPojo conceptMini) {
		ConceptPojo pojo = new ConceptPojo();
		pojo.setActive(true);
		pojo.setConceptId(conceptMini.getConceptId());
		
		if (conceptMini.getDefinitionStatus() != null) {
			pojo.setDefinitionStatus(DefinitionStatus.valueOf(conceptMini.getDefinitionStatus()));
		} else {
			pojo.setDefinitionStatus(DefinitionStatus.PRIMITIVE);
		}
 		
		pojo.setModuleId(conceptMini.getModuleId());
		Set<DescriptionPojo> descriptions = new HashSet<>();
		pojo.setDescriptions(descriptions);
		
		DescriptionPojo inactiveFsnPojo = new DescriptionPojo();
		inactiveFsnPojo.setActive(false);
		inactiveFsnPojo.setTerm("inactive_" + conceptMini.getFsn() );
		inactiveFsnPojo.setCaseSignificance(CaseSignificance.ENTIRE_TERM_CASE_SENSITIVE.name());
		inactiveFsnPojo.setType(DescriptionType.FSN.name());
		descriptions.add(inactiveFsnPojo);
		
		DescriptionPojo fsnPojo = new DescriptionPojo();
		descriptions.add(fsnPojo);
		fsnPojo.setActive(true);
		fsnPojo.setTerm(conceptMini.getFsn());
		fsnPojo.setCaseSignificance(CaseSignificance.CASE_INSENSITIVE.name());
		fsnPojo.setType(DescriptionType.FSN.name());
		DescriptionPojo ptPojo = new DescriptionPojo();
		ptPojo.setTerm(TemplateUtil.getDescriptionFromFSN(conceptMini.getFsn()));
		if ("Aluminium".equals(ptPojo.getTerm())) {
			ptPojo.setAcceptabilityMap(TestDataHelper.constructAcceptabilityMap(Constants.ACCEPTABLE, Constants.PREFERRED));
			DescriptionPojo usPtPojo = new DescriptionPojo();
			usPtPojo.setTerm("Aluminum");
			usPtPojo.setAcceptabilityMap(TestDataHelper.constructAcceptabilityMap(Constants.PREFERRED,Constants.ACCEPTABLE));
			usPtPojo.setActive(true);
			usPtPojo.setType(DescriptionType.SYNONYM.name());
			usPtPojo.setCaseSignificance(CaseSignificance.CASE_INSENSITIVE.name());
			descriptions.add(usPtPojo);
			
		} else {
			ptPojo.setAcceptabilityMap(TestDataHelper.constructAcceptabilityMap(Constants.PREFERRED, Constants.PREFERRED));
		}
		ptPojo.setActive(true);
		ptPojo.setType(DescriptionType.SYNONYM.name());
		ptPojo.setCaseSignificance(CaseSignificance.CASE_INSENSITIVE.name());
		descriptions.add(ptPojo);
		return pojo;
	}
}
