package com.unicarona.notification.service;

import com.unicarona.notification.dto.NotificationDto;
import com.unicarona.notification.entity.Notification;
import com.unicarona.notification.repository.NotificationRepository;
import com.unicarona.shared.ApiDates;
import com.unicarona.shared.ApiException;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final NotificationRepository repository;

  public NotificationService(NotificationRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public UUID notify(UUID user, String title, String body, String route, UUID ref) {
    return repository.save(new Notification(user, title, body, route, ref)).getId();
  }

  @Transactional(readOnly = true)
  public NotificationDto.Page list(UUID user, int limit, int offset) {
    var rows =
        repository.findByUserIdOrderByCreatedAtDescIdAsc(user, new OffsetPage(limit + 1, offset));
    var items =
        rows.stream()
            .limit(limit)
            .map(
                n ->
                    new NotificationDto(
                        n.getId(),
                        n.getTitle(),
                        n.getBody(),
                        n.getRoute(),
                        n.getReference(),
                        ApiDates.timestamp(n.getReadAt()),
                        ApiDates.timestamp(n.getCreatedAt())))
            .toList();
    return new NotificationDto.Page(
        items,
        repository.countByUserIdAndReadAtIsNull(user),
        rows.size() > limit ? offset + limit : null);
  }

  @Transactional
  public NotificationDto.Read read(UUID user, UUID id) {
    ApiException.require(
        repository.markRead(id, user) == 1, "NOTIFICATION_NOT_FOUND", "Aviso não encontrado.", 404);
    var notice =
        repository
            .findByIdAndUserId(id, user)
            .orElseThrow(
                () -> new ApiException("NOTIFICATION_NOT_FOUND", "Aviso não encontrado.", 404));
    return new NotificationDto.Read(id, ApiDates.timestamp(notice.getReadAt()));
  }

  /** Arbitrary offset compatibility; PageRequest(offset/limit) would drop a partial page. */
  private record OffsetPage(int size, long offset) implements Pageable {
    public int getPageNumber() {
      return (int) (offset / size);
    }

    public int getPageSize() {
      return size;
    }

    public long getOffset() {
      return offset;
    }

    public Sort getSort() {
      return Sort.unsorted();
    }

    public Pageable next() {
      return new OffsetPage(size, offset + size);
    }

    public Pageable previousOrFirst() {
      return new OffsetPage(size, Math.max(0, offset - size));
    }

    public Pageable first() {
      return new OffsetPage(size, 0);
    }

    public Pageable withPage(int page) {
      return new OffsetPage(size, (long) page * size);
    }

    public boolean hasPrevious() {
      return offset > 0;
    }
  }
}
