package com.unicarona.notification.dto;

import java.util.List;
import java.util.UUID;

public record NotificationDto(UUID id,String title,String body,String route,UUID ref,String readAt,String createdAt) {
    public record Page(List<NotificationDto> items,long unread,Integer nextOffset) {}
    public record Read(UUID id,String readAt) {}
}
