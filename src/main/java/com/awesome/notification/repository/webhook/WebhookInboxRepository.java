package com.awesome.notification.repository.webhook;

import com.awesome.notification.entity.webhook.WebhookInboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookInboxRepository extends JpaRepository<WebhookInboxEntity, Long> {

    Optional<WebhookInboxEntity> findByToken(String token);

    List<WebhookInboxEntity> findByUsernameOrderByIdAsc(String username);

    long countByUsername(String username);
}
