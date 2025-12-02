package com.messenger.edge_service.model.utils;

import com.messenger.edge_service.model.dto.*;
import com.messenger.edge_service.model.enums.EventType;
import com.messenger.edge_service.model.enums.MessageStatus;

import java.time.Instant;
import java.util.List;

public class EnvelopeFactory {

    public static MessageEnvelope createAck(String chatId, String tempId, String realId) {
        return MessageEnvelope.builder()
                .type(EventType.ACK)
                .chatId(chatId)
                .tempId(tempId)
                .realId(realId)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static MessageEnvelope createEnrichedMessage(
            String chatId,
            String userId,
            String senderName,
            String senderAvatar,
            String realId,
            String content,
            List<MediaDTO> media,
            String replyTo
    ) {
        return MessageEnvelope.builder()
                .type(EventType.MESSAGE)
                .realId(realId)
                .chatId(chatId)
                .senderId(userId)
                .senderName(senderName)
                .senderAvatar(senderAvatar)
                .content(content)
                .mediaDTOS(media)
                .replyTo(replyTo)
                .isEdited(false)
                .status(MessageStatus.SENT)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static MessageEnvelope createEditEvent(
            String realId,
            String chatId,
            String senderId,
            String content,
            List<MediaDTO> media
    ) {
        return MessageEnvelope.builder()
                .type(EventType.EDIT)
                .realId(realId)
                .chatId(chatId)
                .senderId(senderId)
                .timestamp(Instant.now().toEpochMilli())
                .edit(new EditEvent(content, media))
                .build();
    }

    public static MessageEnvelope createDeleteEvent(
            String realId,
            String chatId,
            String userId
    ) {
        return MessageEnvelope.builder()
                .type(EventType.DELETE)
                .realId(realId)
                .chatId(chatId)
                .senderId(userId)
                .delete(new DeleteEvent(true))
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static MessageEnvelope createTypingEvent(String chatId, String userId) {
        return MessageEnvelope.builder()
                .type(EventType.TYPING)
                .chatId(chatId)
                .senderId(userId)
                .timestamp(Instant.now().toEpochMilli())
                .typing(true)
                .build();
    }

    public static MessageEnvelope createOnline(String userId, boolean isOnline) {
        return MessageEnvelope.builder()
                .type(EventType.ONLINE)
                .senderId(userId)
                .online(new OnlineEvent(isOnline, null))
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static MessageEnvelope createRead(String chatId, String senderId, String receiverId, String readUpTo) {
        return MessageEnvelope.builder()
                .type(EventType.READ)
                .chatId(chatId)
                .senderId(senderId)
                .receiverId(receiverId)
                .readUpTo(readUpTo)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static MessageEnvelope createDelivered(String chatId, String realId, String senderId, String receiverId) {
        return MessageEnvelope.builder()
                .type(EventType.DELIVERED)
                .chatId(chatId)
                .senderId(senderId)
                .receiverId(receiverId)
                .realId(realId)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

}
