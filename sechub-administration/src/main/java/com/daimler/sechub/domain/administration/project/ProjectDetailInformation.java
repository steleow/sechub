// SPDX-License-Identifier: MIT
package com.daimler.sechub.domain.administration.project;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.daimler.sechub.domain.administration.user.User;

public class ProjectDetailInformation {

	public static final String PROPERTY_USERS = "users";
	public static final String PROPERTY_PROJECT_ID = "projectId";
	public static final String PROPERTY_WHITELIST = "whiteList";
	public static final String PROPERTY_METADATA = "metaData";
	public static final String PROPERTY_OWNER = "owner";

	private String projectId;

	private List<String> users = new ArrayList<>();
	private List<String> whitelist = new ArrayList<>();
	private Map<String, String> metaData = new HashMap<>();
	private String owner;

	public ProjectDetailInformation(Project project) {
		this.projectId = project.getId();

		for (User user: project.getUsers()) {
			this.users.add(user.getName());
		}
		
		project.getWhiteList().forEach(uri -> this.whitelist.add(uri.toASCIIString()));
		
		project.metaData.forEach(entry -> this.metaData.put(entry.key, entry.value));
		
		this.owner= project.getOwner().getName();
	}

	public String getOwner() {
		return owner;
	}

	public List<String> getWhiteList() {
		return whitelist;
	}
	
	public Map<String, String> getMetaData() {
		return metaData;
	}

	public List<String> getUsers() {
		return users;
	}

	public String getProjectId() {
		return projectId;
	}

}
