package org.ihtsdo.otf.authoringtemplate.service;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.ihtsdo.otf.authoringtemplate.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {Config.class, TestHarnessConfig.class})
@PropertySource("classpath:application-test.properties")
public class TemplateConceptSearchServiceTestHarness {
		
		private static final String TEMPLATES_DIR = "/Users/mchu/Development/snomed-templates/";

		private static final String JSON = ".json";
		
		@Autowired
		private TemplateConceptSearchService searchService;
		
		@Autowired
		private TemplateService templateService;

		@Autowired
		private JsonStore jsonStore;
		
		private String source;

		
		@Before
		public void setUp() throws Exception {
			String sourceFilename = "Allergy to [substance] (disorder) v1 OUTDATED.json";
			source = "Allergy to [substance] (disorder) - OUTDATED";
			FileUtils.copyFileToDirectory(new File(TEMPLATES_DIR +  sourceFilename),
					jsonStore.getStoreDirectory());
			templateService.reloadCache();
//			String singleSignOnCookie = "Add_token_here";
			String singleSignOnCookie = "Ac95USRhW1f5DeGEAwQgtA00";
			AbstractAuthenticationToken token = new PreAuthenticatedAuthenticationToken("", singleSignOnCookie);
			SecurityContextHolder.getContext().setAuthentication(token);
		}
		
		@Test
		public void testTemplateConceptSearch() throws Exception {
			searchService.searchConceptsByTemplate(source, "MAIN", new TemplateSearchRequest(true, true));
		}
}
