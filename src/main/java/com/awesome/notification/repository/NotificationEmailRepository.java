package com.awesome.notification.repository;

import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.entity.NotificationEmailEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationEmailRepository extends JpaRepository<NotificationEmailEntity, UUID> {

    boolean existsByRequestId(UUID requestId);

    Page<NotificationEmailEntity> findByUsername(String username, Pageable pageable);

    long countByStatus(NotificationEmailStatus status);

    /** QUEUED emails whose next attempt is due, oldest first. */
    @Query("""
            SELECT e.id FROM NotificationEmailEntity e
            WHERE e.status = :queued AND e.nextAttemptAt <= :now
            ORDER BY e.nextAttemptAt, e.createdAt""")
    List<UUID> findDueIds(@Param("queued") NotificationEmailStatus queued, @Param("now") Instant now, Pageable pageable);

    /** Claims a due QUEUED email for one attempt by moving its next attempt past {@code leaseUntil}; 1 when won. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE NotificationEmailEntity e SET e.nextAttemptAt = :leaseUntil
            WHERE e.id = :id AND e.status = :queued AND e.nextAttemptAt <= :now""")
    int claim(@Param("id") UUID id, @Param("queued") NotificationEmailStatus queued, @Param("now") Instant now,
              @Param("leaseUntil") Instant leaseUntil);
}
