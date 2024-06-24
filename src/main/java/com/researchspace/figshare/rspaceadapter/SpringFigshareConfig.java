package com.researchspace.figshare.rspaceadapter;

import com.researchspace.zipprocessing.ArchiveIterator;
import com.researchspace.zipprocessing.ArchiveIteratorImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class SpringFigshareConfig {
	
	@Bean
	ArchiveIterator ArchiveIterator (){
		return new ArchiveIteratorImpl();
	}

}
