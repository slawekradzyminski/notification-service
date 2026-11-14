package com.awesome.notification.service.email;

import com.awesome.notification.controller.exception.CustomException;
import com.awesome.notification.dto.PageDto;
import com.awesome.notification.dto.email.NotificationEmailDto;
import com.awesome.notification.repository.NotificationEmailRepository;
import com.awesome.notification.security.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The commerce emails of a user, newest first. */
@Service
@RequiredArgsConstructor
public class NotificationEmailQueries {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationEmailRepository emails;

    /**
     * The caller's emails; an administrator may name another {@code username}, anyone else gets 403
     * for a username other than their own.
     */
    @Transactional(readOnly = true)
    public PageDto<NotificationEmailDto> list(CustomPrincipal caller, String username, int page, int size) {
        String owner = username == null || username.isBlank() ? caller.username() : username.trim();
        if (!owner.equals(caller.username()) && !caller.isAdmin()) {
            throw new CustomException("Only administrators can read another user's emails", HttpStatus.FORBIDDEN);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageDto.from(emails.findByUsername(owner, pageable).map(NotificationEmailDto::from));
    }
}
