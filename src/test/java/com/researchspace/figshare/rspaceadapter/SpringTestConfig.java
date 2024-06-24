package com.researchspace.figshare.rspaceadapter;

import com.researchspace.figshare.impl.FigshareTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Test configuration. Requires personal token set as system property e.g. -DfigshareToken=XXX 
 */
@Configuration
public class SpringTestConfig {
	
	@Autowired Environment env;
	
	@Bean
	FigshareTemplate figshareTemplate() {
		return new FigshareTemplate(env.getProperty("figshareToken"));
	}
	
	@Bean
	FigshareRSpaceRepository figshareRSpaceRepository() {
		FigshareRSpaceRepository repo = new  FigshareRSpaceRepository();
		repo.setFigshare(figshareTemplate());
		return repo;
	}
	
}
