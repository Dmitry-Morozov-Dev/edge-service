package com.messenger.edge_service.service.wsReceivePubsubProduce;

import com.messenger.edge_service.model.dto.MediaDTO;
import com.messenger.edge_service.model.dto.MessageEnvelope;
import com.messenger.edge_service.service.session.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageValidationService {

    private final SessionManager sessionManager;

    @Value("${edge.validation.message.content-max-length:4096}")
    private int contentMaxLength;

    @Value("${edge.validation.message.temp-id-max-length:200}")
    private int tempIdMaxLength;

    @Value("${edge.validation.message.media-max-count:10}")
    private int mediaMaxCount;

    @Value("${edge.validation.message.media-url-max-length:2000}")
    private int mediaUrlMaxLength;

    public void validateMessage(MessageEnvelope env, String userId) {
        validateChatId(env.getChatId());
        validateTempId(env.getTempId());
        validateSenderConsistency(env.getSenderId(), userId);
        validateMembership(env.getChatId(), userId);

        boolean hasContent = env.getContent() != null && !env.getContent().isBlank();
        boolean hasMedia = env.getMediaDTOS() != null && !env.getMediaDTOS().isEmpty();

        if (!hasContent && !hasMedia) {
            throw new IllegalArgumentException("content or media must be present");
        }

        if (hasContent && env.getContent().length() > contentMaxLength) {
            throw new IllegalArgumentException("content too large");
        }

        if (hasMedia) {
            validateMedia(env.getMediaDTOS());
        }

        if (env.getReplyTo() != null && !isValidUUID(env.getReplyTo())) {
            throw new IllegalArgumentException("replyTo has invalid UUID format");
        }
    }

    public void validateEdit(MessageEnvelope env, String userId) {
        validateRealId(env.getRealId(), "realId is required for edit");
        validateChatId(env.getChatId());
        validateSenderConsistency(env.getSenderId(), userId);
        validateMembership(env.getChatId(), userId);

        boolean hasContent = env.getContent() != null && !env.getContent().isBlank();
        boolean hasMedia = env.getMediaDTOS() != null && !env.getMediaDTOS().isEmpty();

        if (!hasContent && !hasMedia) {
            throw new IllegalArgumentException("either content or media must be provided for edit");
        }

        if (hasContent && env.getContent().length() > contentMaxLength) {
            throw new IllegalArgumentException("content too large");
        }

        if (hasMedia) {
            validateMedia(env.getMediaDTOS());
        }
    }

    public void validateDelete(MessageEnvelope env, String userId) {
        validateRealId(env.getRealId(), "realId is required for delete");
        validateChatId(env.getChatId());
        validateSenderConsistency(env.getSenderId(), userId);
        validateMembership(env.getChatId(), userId);

        if (env.getContent() != null) {
            throw new IllegalArgumentException("delete event must not contain content");
        }
        if (env.getMediaDTOS() != null && !env.getMediaDTOS().isEmpty()) {
            throw new IllegalArgumentException("delete event must not contain media");
        }
        if (env.getReplyTo() != null) {
            throw new IllegalArgumentException("replyTo is not allowed for delete");
        }
    }

    public void validateTyping(MessageEnvelope env, String userId) {
        validateChatId(env.getChatId());
        if (env.getTyping() == null) {
            throw new IllegalArgumentException("isTyping is required");
        }
        validateMembership(env.getChatId(), userId);

        if (env.getContent() != null ||
                env.getMediaDTOS() != null ||
                env.getTempId() != null ||
                env.getRealId() != null ||
                env.getReplyTo() != null) {
            throw new IllegalArgumentException("typing event must not include message fields");
        }
    }

    public void validateRead(MessageEnvelope env, String userId) {
        validateChatId(env.getChatId());
        validateReadUpTo(env.getReadUpTo());
        validateSenderConsistency(env.getSenderId(), userId);
        validateReceiverId(env.getReceiverId());
        validateMembership(env.getChatId(), userId);
        validateSelfDelivery(env.getSenderId(), env.getReceiverId());

        if (env.getContent() != null ||
                env.getMediaDTOS() != null ||
                env.getTempId() != null ||
                env.getRealId() != null ||
                env.getReplyTo() != null ||
                env.getIsEdited() != null ||
                env.getEdit() != null ||
                env.getDelete() != null ||
                env.getTyping() != null ||
                env.getOnline() != null) {
            throw new IllegalArgumentException("read event must not include message fields");
        }
    }

    public void validateDelivered(MessageEnvelope env, String userId) {
        validateChatId(env.getChatId());
        validateRealId(env.getRealId(), "realId is required");
        validateSenderConsistency(env.getSenderId(), userId);
        validateReceiverId(env.getReceiverId());
        validateMembership(env.getChatId(), userId);
        validateSelfDelivery(env.getSenderId(), env.getReceiverId());

        if (env.getContent() != null ||
                env.getMediaDTOS() != null ||
                env.getTempId() != null ||
                env.getReplyTo() != null ||
                env.getIsEdited() != null ||
                env.getEdit() != null ||
                env.getDelete() != null ||
                env.getTyping() != null ||
                env.getOnline() != null ||
                env.getReadUpTo() != null) {
            throw new IllegalArgumentException("delivered event must not include message fields");
        }
    }

    private void validateChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (!isValidUUID(chatId)) {
            throw new IllegalArgumentException("chatId format invalid");
        }
    }

    private void validateTempId(String tempId) {
        if (tempId == null || tempId.isBlank()) {
            throw new IllegalArgumentException("tempId is required");
        }
        if (tempId.length() > tempIdMaxLength) {
            throw new IllegalArgumentException("tempId too long");
        }
    }

    private void validateRealId(String realId, String message) {
        if (realId == null || realId.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!isValidUUID(realId)) {
            throw new IllegalArgumentException("realId must be valid UUID");
        }
    }

    private void validateReadUpTo(String readUpTo) {
        if (readUpTo == null || readUpTo.isBlank()) {
            throw new IllegalArgumentException("readUpTo is required");
        }
        if (!isValidUUID(readUpTo)) {
            throw new IllegalArgumentException("readUpTo must be valid UUID");
        }
    }

    private void validateReceiverId(String receiverId) {
        if (receiverId == null || receiverId.isBlank()) {
            throw new IllegalArgumentException("receiverId is required");
        }
        if (!isValidUUID(receiverId)) {
            throw new IllegalArgumentException("receiverId format invalid");
        }
    }

    private void validateSenderConsistency(String senderId, String userId) {
        if (senderId != null && !senderId.equals(userId)) {
            throw new IllegalArgumentException("senderId mismatch");
        }
    }

    private void validateSelfDelivery(String senderId, String receiverId) {
        if (senderId.equals(receiverId)) {
            throw new IllegalArgumentException("sender == receiver");
        }
    }

    private void validateMembership(String chatId, String userId) {
        Set<String> userSessionIds = sessionManager.getUserSessions(userId);
        Map<String, WebSocketSession> chatSessions = sessionManager.getChatSessions(chatId);

        if (chatSessions == null || chatSessions.isEmpty()) {
            throw new IllegalArgumentException("chat does not exist or has no active members");
        }

        boolean participates = userSessionIds.stream()
                .anyMatch(chatSessions::containsKey);

        if (!participates) {
            throw new IllegalArgumentException("user is not a participant of this chat");
        }
    }

    private void validateMedia(List<MediaDTO> mediaList) {
        if (mediaList.size() > mediaMaxCount) {
            throw new IllegalArgumentException("too many media items");
        }
        for (var media : mediaList) {
            if (media.url() == null || media.url().isBlank()) {
                throw new IllegalArgumentException("media url required");
            }
            if (media.url().length() > mediaUrlMaxLength) {
                throw new IllegalArgumentException("media url too long");
            }
            if (media.type() == null) {
                throw new IllegalArgumentException("media type required");
            }
        }
    }

    public boolean isValidUUID(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}