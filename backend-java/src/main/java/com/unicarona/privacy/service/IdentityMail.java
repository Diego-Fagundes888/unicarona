package com.unicarona.privacy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.notification.template.AuthEmailTemplate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Encrypted destination, URL and frozen content; never persist a raw confirmation token in the
 * outbox.
 */
@Service
public class IdentityMail {
  private final JdbcTemplate sql;
  private final ObjectMapper json;
  private final PrivacyEvidenceCrypto evidence;
  private final EmailSettings email;

  public IdentityMail(
      JdbcTemplate sql, ObjectMapper json, PrivacyEvidenceCrypto evidence, EmailSettings email) {
    this.sql = sql;
    this.json = json;
    this.evidence = evidence;
    this.email = email;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(UUID owner, String destination, String kind, String url, String event) {
    try {
      var content =
          new AuthEmailTemplate(email)
              .render(new EmailProvider.Message(UUID.randomUUID(), destination, kind, url));
      var payload =
          Map.of(
              "ownerId",
              owner.toString(),
              "toCiphertext",
              evidence.sealMail(PrivacyEvidenceCrypto.Field.MAIL_TO, owner, event, destination),
              "kind",
              kind,
              "urlCiphertext",
              evidence.sealMail(PrivacyEvidenceCrypto.Field.MAIL_URL, owner, event, url),
              "contentCiphertext",
              evidence.sealMail(
                  PrivacyEvidenceCrypto.Field.MAIL_CONTENT,
                  owner,
                  event,
                  json.writeValueAsString(content)));
      sql.update(
          "INSERT INTO outbox_jobs(type,event_key,payload) VALUES('email',?,?::jsonb) ON CONFLICT(event_key) DO NOTHING",
          event,
          json.writeValueAsString(payload));
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Could not protect identity email.");
    }
  }
}
