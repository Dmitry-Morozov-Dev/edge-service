package com.messenger.edge_service.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EditEvent(
        @JsonProperty("new_content") String newContent,
        @JsonProperty("new_media") List<MediaDTO> newMediaDTO
) {}