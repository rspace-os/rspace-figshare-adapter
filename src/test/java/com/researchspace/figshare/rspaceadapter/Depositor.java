package com.researchspace.figshare.rspaceadapter;

import com.researchspace.repository.spi.ExternalId;
import com.researchspace.repository.spi.IDepositor;
import lombok.Value;

import java.util.List;
// simple implementation for testing purposes
@Value
public class Depositor implements IDepositor {

	private String email, uniqueName;
	private List<ExternalId> externalIds;

}
