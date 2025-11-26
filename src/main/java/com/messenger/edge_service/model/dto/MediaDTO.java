package com.messenger.edge_service.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.messenger.edge_service.model.enums.MediaType;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaDTO(
        @JsonProperty("media_id") UUID mediaId,
        @JsonProperty("type") MediaType type,
        @JsonProperty("url") String url,
        @JsonProperty("thumbnail_url") String thumbnailUrl
) {}
