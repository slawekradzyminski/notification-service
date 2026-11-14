package com.awesome.notification.repository.webhook;

import com.awesome.notification.entity.webhook.WebhookSubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscriptionEntity, Long> {

    List<WebhookSubscriptionEntity> findByUsernameOrderByIdAsc(String username);

    List<WebhookSubscriptionEntity> findByUsernameAndActiveTrueOrderByIdAsc(String username);

    long countByUsername(String username);

    Page<WebhookSubscriptionEntity> findByUsername(String username, Pageable pageable);

    List<WebhookSubscriptionEntity> findByUrl(String url);
}
