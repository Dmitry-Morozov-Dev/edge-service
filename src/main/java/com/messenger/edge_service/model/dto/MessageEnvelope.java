package com.messenger.edge_service.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.messenger.edge_service.model.enums.EventType;
import com.messenger.edge_service.model.enums.MessageStatus;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Value
@Builder
public class MessageEnvelope {
    EventType type;
    String tempId;
    String realId;
    String chatId;
    String senderId;
    String senderName;
    String senderAvatar;
    String receiverId;
    String content;
    List<MediaDTO> mediaDTOS;
    String replyTo;
    Boolean isEdited;
    MessageStatus status;
    Long timestamp;
    Boolean typing;
    OnlineEvent online;
    EditEvent edit;
    DeleteEvent delete;
    String readUpTo;
}