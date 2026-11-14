package com.awesome.notification.dto.email;

/** QUEUED until the transport accepted the email (retried with backoff), then SENT; FAILED once retries are exhausted. */
public enum NotificationEmailStatus {
    QUEUED,
    SENT,
    FAILED
}
