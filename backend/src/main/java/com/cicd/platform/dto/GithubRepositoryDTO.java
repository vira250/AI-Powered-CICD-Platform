package com.cicd.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GithubRepositoryDTO(
        Long id,
        String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("private") boolean privateRepository,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("default_branch") String defaultBranch,
        String description,
        GithubRepositoryOwnerDTO owner
) {
}