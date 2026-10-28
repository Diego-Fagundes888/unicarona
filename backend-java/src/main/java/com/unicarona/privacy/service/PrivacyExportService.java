package com.unicarona.privacy.service;

import com.fasterxml.jackson.core.*;
import com.unicarona.auth.service.AuthService;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.AuditRepository;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Streams an owner-scoped ZIP; neither a public URL nor a generated disk file is created. */
@Service
public class PrivacyExportService {
  private final JdbcTemplate sql;
  private final PrivacyReauthentication reauthentication;
  private final AuthService auth;
  private final AuditRepository audit;
  private final PrivateFileStorage storage;
  private final PrivacyEvidenceCrypto evidence;

  public PrivacyExportService(
      JdbcTemplate sql,
      PrivacyReauthentication reauthentication,
      AuthService auth,
      AuditRepository audit,
      PrivateFileStorage storage,
      PrivacyEvidenceCrypto evidence) {
    this.sql = sql;
    this.reauthentication = reauthentication;
    this.auth = auth;
    this.audit = audit;
    this.storage = storage;
    this.evidence = evidence;
  }

  public void authorize(Actor actor, String password) {
    reauthentication.confirm(actor, password);
    auth.limit("privacy-export:" + actor.id(), 3, 3600);
    audit.record(
        actor.id(),
        "privacy_export_requested",
        "user",
        actor.id(),
        Map.of("format", "zip-json-v1"));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public void write(UUID owner, OutputStream destination) throws IOException {
    // One consistent authorized snapshot, without locking payment writers while the client
    // downloads.
    ApiException.require(
        !sql.queryForList("SELECT id FROM users WHERE id=? AND deleted_at IS NULL", owner)
            .isEmpty(),
        "ACCOUNT_UNAVAILABLE",
        "A conta não está disponível para exportação.",
        409);
    try (var zip = new ZipOutputStream(destination)) {
      zip.putNextEntry(new ZipEntry("dados.json"));
      var factory = new JsonFactory();
      factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
      try (var json = factory.createGenerator(zip)) {
        json.writeStartObject();
        json.writeStringField("format", "unicarona-personal-data-v1");
        json.writeStringField(
            "generatedAt",
            sql.queryForObject("SELECT now()", Timestamp.class).toInstant().toString());
        json.writeStringField(
            "scope",
            "Dados do titular e registros de suas relações. Credenciais, evidências internas de segurança e dados privados de terceiros não são incluídos. Complementos podem ser solicitados pelo centro de privacidade.");
        rows(
            json,
            "profile",
            "SELECT id,name,email,birth_date,phone,role,account_status,voluntarily_deactivated_at,email_verified_at,created_at,updated_at FROM users WHERE id=?",
            owner);
        rows(
            json,
            "emailChanges",
            "SELECT id,new_email_ciphertext,status,created_at,expires_at,completed_at FROM privacy_email_changes WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "accountDecisions",
            "SELECT id,action,previous_status,new_status,reason_code,public_reason,created_at FROM privacy_account_decisions WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "academic",
            "SELECT user_id,university_id,course_id,class_id,period_status,period_number,shift,region_id,region_label,verification_status,verification_source,verified_at,notices,withdrawal_cadence FROM student_profiles WHERE user_id=?",
            owner);
        rows(
            json,
            "driver",
            "SELECT user_id,status,approved_at,updated_at FROM driver_profiles WHERE user_id=?",
            owner);
        rows(
            json,
            "vehicles",
            "SELECT id,model,color,plate,capacity,status,created_at,updated_at,deleted_at FROM vehicles WHERE owner_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "driverApplications",
            "SELECT a.id,a.vehicle_id,a.status,CASE WHEN "
                + PrivacyDriverApplicationRetention.REVIEW_CURRENT
                + " THEN a.decision END AS decision,a.created_at,a.reviewed_at,a.documents_expires_at,a.documents_retired_at,a.documents_policy_version FROM driver_applications a WHERE a.user_id=? ORDER BY a.created_at,a.id",
            owner);
        rows(
            json,
            "documents",
            "SELECT id,purpose,content_type,byte_size,created_at,deleted_at,erasure_requested_at,erased_at FROM stored_files WHERE owner_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "routes",
            "SELECT id,university_id,CASE WHEN privacy_route_details_visible(rt) OR privacy_route_details_case(rt.id) THEN region END AS region,CASE WHEN privacy_route_details_visible(rt) OR privacy_route_details_case(rt.id) THEN meeting END AS meeting,origin_address,destination_address,origin_lat,origin_lng,destination_lat,destination_lng,distance_m,created_at,details_retired_at FROM ride_routes rt WHERE driver_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "offeredRides",
            "SELECT id,route_id,routine_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,toll_cents,automatic,CASE WHEN privacy_ride_details_visible(r) OR privacy_route_details_case(r.route_id) THEN notes END AS notes,status,proposed_time,CASE WHEN privacy_ride_details_visible(r) OR privacy_route_details_case(r.route_id) THEN proposal_reason END AS proposal_reason,started_at,completed_at,created_at,details_expires_at,details_retired_at FROM rides r WHERE driver_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "routines",
            "SELECT id,route_id,vehicle_id,class_id,weekdays,ida_time,volta_time,ida_cents,volta_cents,capacity,automatic,status,CASE WHEN privacy_routine_details_visible(r) OR privacy_route_details_case(r.route_id) THEN notes END AS notes,created_at,details_inactive_since,details_retired_at FROM routines r WHERE driver_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "requests",
            "SELECT id,type,status,reason,expires_at,created_at,updated_at FROM ride_requests WHERE passenger_id=? OR driver_id=? ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "reservations",
            "SELECT id,request_id,ride_id,subscription_id,payment_id,status,price_cents,boarded_at,final_response,availability,absence,absence_reason,confirmation_deadline,cancel_reason,pin_expires_at,pin_erased_at,created_at,updated_at FROM ride_reservations WHERE passenger_id=? OR driver_id=? ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "subscriptions",
            "SELECT id,routine_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status,renew,grace_until,created_at,updated_at FROM subscriptions WHERE passenger_id=? OR driver_id=? ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "requestLines",
            "SELECT l.request_id,l.ride_id,l.cents,l.commission_bps FROM request_lines l JOIN ride_requests r ON r.id=l.request_id WHERE r.passenger_id=? OR r.driver_id=? ORDER BY l.request_id,l.ride_id",
            owner,
            owner);
        rows(
            json,
            "subscriptionSchedule",
            "SELECT sc.subscription_id,sc.ride_id,sc.reservation_id,sc.cents,sc.held FROM subscription_schedule sc JOIN subscriptions s ON s.id=sc.subscription_id WHERE s.passenger_id=? OR s.driver_id=? ORDER BY sc.subscription_id,sc.ride_id",
            owner,
            owner);
        rows(
            json,
            "waitlist",
            "SELECT id,routine_id,priority,status,created_at FROM waitlist WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "payments",
            "SELECT id,request_id,amount_cents,credit_cents,external_cents,provider,provider_reference,status,method,expires_at,paid_at,created_at,updated_at FROM payments WHERE passenger_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "parcels",
            "SELECT id,reservation_id,payment_id,cents,commission_bps,status,commission_cents,driver_cents,settled_at,created_at FROM parcels WHERE passenger_id=? OR driver_id=? ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "ledger",
            "SELECT e.id,e.transaction_id,a.kind,a.currency,e.cents,e.created_at FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE a.owner_id=? ORDER BY e.created_at,e.id",
            owner);
        rows(
            json,
            "transfers",
            "SELECT id,type,cents,status,cadence,provider_reference,decision,created_at,updated_at FROM transfers WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "debtAssessments",
            "SELECT payment_id,reversal_cents,held_credit_cents,debt_cents,reason,created_at,updated_at FROM passenger_debt_assessments WHERE passenger_id=? ORDER BY created_at,payment_id",
            owner);
        rows(
            json,
            "debtPayments",
            "SELECT d.id,d.amount_cents,CASE WHEN privacy_debt_payer_required(d) THEN d.payer_email END AS payer_email,d.state,d.order_id,d.paid_at,d.refunded_cents,d.expires_at,d.created_at,d.updated_at FROM passenger_debt_payments d WHERE passenger_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "refunds",
            "SELECT id,payment_id,amount_cents,reason,status,provider_reference,created_at,updated_at FROM mercado_pago_refunds WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "notifications",
            "SELECT id,title,body,route,read_at,created_at FROM notifications WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "ratingsWritten",
            "SELECT id,reservation_id,score,comment,created_at FROM ratings WHERE by_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "ratingsReceived",
            "SELECT id,reservation_id,score,comment,created_at FROM ratings WHERE other_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "reportsSubmitted",
            "SELECT id,reservation_id,kind,reason,severe,status,outcome,decision,reviewed_at,created_at FROM occurrences WHERE reporter_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "reportsInvolvingAccount",
            "SELECT id,kind,status,outcome,reviewed_at,created_at FROM occurrences WHERE other_id=? AND reporter_id<>? ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "blocksCreated",
            "SELECT id,other_id,active,created_at,updated_at FROM user_blocks WHERE by_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "legalAcceptances",
            "SELECT id,document_slug,document_version,kind,content_sha256,source,accepted_at,retain_until FROM legal_acceptances WHERE user_id=? ORDER BY accepted_at,id",
            owner);
        rows(
            json,
            "legalNotifications",
            "SELECT document_slug,document_version,notified_at FROM legal_change_notices WHERE user_id=? ORDER BY notified_at,document_slug",
            owner);
        rows(
            json,
            "privacyPreferences",
            "SELECT marketing,marketing_statement_version,updated_at FROM privacy_preferences WHERE user_id=?",
            owner);
        rows(
            json,
            "consents",
            "SELECT id,purpose,granted,statement_version,source,created_at,retain_until FROM privacy_consent_events WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "privacyRequests",
            "SELECT id,kind,details_ciphertext,status,created_at,due_at,updated_at,resolved_at,details_retired_at FROM privacy_requests WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "privacyResponses",
            "SELECT r.id,r.request_id,r.body_ciphertext,r.created_at FROM privacy_request_responses r JOIN privacy_requests p ON p.id=r.request_id WHERE p.user_id=? ORDER BY r.created_at,r.id",
            owner);
        rows(
            json,
            "sessionHistory",
            "SELECT created_at,last_seen_at,access_expires_at,refresh_expires_at,revoked_at FROM sessions WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "accountEvents",
            "SELECT id,action,resource_type,resource_id,created_at FROM audit_logs a WHERE (actor_id=? OR (resource_type='user' AND resource_id=?)) AND (privacy_security_audit_deadline(a) IS NULL OR privacy_security_audit_deadline(a)>clock_timestamp()) ORDER BY created_at,id",
            owner,
            owner);
        rows(
            json,
            "closures",
            "SELECT id,initiated_by,status,requested_at,erase_after,cancelled_at,completed_at,policy_version FROM privacy_account_closures WHERE user_id=? ORDER BY requested_at,id",
            owner);
        rows(
            json,
            "retainedCategories",
            "SELECT category,legal_basis,reason,created_at,delete_after FROM privacy_retained_records WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "retainedIdentityCopies",
            "SELECT id,category,content_ciphertext,created_at,delete_after,hold_only FROM privacy_retained_records WHERE user_id=? AND category IN ('profile','contract_identity','vehicle_identity') ORDER BY created_at,id",
            owner);
        rows(
            json,
            "retainedDriverApplications",
            "SELECT id,category,content_ciphertext,created_at,delete_after,hold_only FROM privacy_retained_records WHERE user_id=? AND category='driver_application' AND EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=privacy_retained_records.user_id AND h.category='driver_application' AND (h.resource_id IS NULL OR h.resource_id=privacy_retained_records.resource_id) AND h.released_at IS NULL AND h.expires_at>now()) ORDER BY created_at,id",
            owner);
        rows(
            json,
            "retainedSecurityEvents",
            "SELECT id,category,content_ciphertext,created_at,delete_after,hold_only FROM privacy_retained_records WHERE user_id=? AND category='security_audit' AND EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=privacy_retained_records.user_id AND h.category='security_audit' AND (h.resource_id IS NULL OR h.resource_id=privacy_retained_records.resource_id) AND h.released_at IS NULL AND h.expires_at>now()) ORDER BY created_at,id",
            owner);
        rows(
            json,
            "holds",
            "SELECT category,legal_basis,created_at,review_at,expires_at,released_at FROM privacy_legal_holds WHERE user_id=? ORDER BY created_at,id",
            owner);
        rows(
            json,
            "holdDecisions",
            "SELECT hold_id,revision,action,created_at,retain_until FROM privacy_hold_events WHERE user_id=? ORDER BY created_at,id",
            owner);
        json.writeEndObject();
      }
      zip.closeEntry();
      var files =
          sql.queryForList(
              "SELECT f.id,f.storage_key,f.content_type FROM stored_files f WHERE f.owner_id=? AND f.deleted_at IS NULL AND f.erasure_requested_at IS NULL AND f.erased_at IS NULL AND (f.purpose='photo' OR "
                  + com.unicarona.file.repository.FileAccessRepository.LIVE_DOCUMENT_PURPOSE
                  + ") ORDER BY f.id",
              owner);
      var unavailable = new ArrayList<UUID>();
      int included = 0;
      for (var file : files) {
        String extension =
            switch ((String) file.get("content_type")) {
              case "image/jpeg" -> ".jpg";
              case "image/png" -> ".png";
              case "image/webp" -> ".webp";
              case "application/pdf" -> ".pdf";
              default -> ".bin";
            };
        byte[] bytes;
        try {
          bytes = storage.get((String) file.get("storage_key"));
        } catch (java.nio.file.NoSuchFileException alreadyRemoved) {
          unavailable.add((UUID) file.get("id"));
          continue;
        }
        zip.putNextEntry(new ZipEntry("documentos/" + file.get("id") + extension));
        zip.write(bytes);
        zip.closeEntry();
        included++;
      }
      if (!unavailable.isEmpty()) {
        zip.putNextEntry(new ZipEntry("arquivos-indisponiveis.json"));
        var reportFactory = new JsonFactory();
        reportFactory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        try (var report = reportFactory.createGenerator(zip)) {
          report.writeStartObject();
          report.writeStringField(
              "message",
              "Estes arquivos deixaram de estar disponíveis durante a exportação. Os registros atuais estão em dados.json; complementos podem ser solicitados pelo centro de privacidade.");
          report.writeArrayFieldStart("files");
          for (UUID file : unavailable) report.writeString(file.toString());
          report.writeEndArray();
          report.writeEndObject();
        }
        zip.closeEntry();
      }
      zip.finish();
      zip.flush();
      audit.record(
          owner,
          "privacy_export_completed",
          "user",
          owner,
          Map.of(
              "format", "zip-json-v1", "files", included, "unavailableFiles", unavailable.size()));
    }
  }

  private void rows(JsonGenerator json, String category, String query, Object... parameters)
      throws IOException {
    json.writeArrayFieldStart(category);
    try {
      sql.query(
          connection -> {
            var statement = connection.prepareStatement(query);
            statement.setFetchSize(128);
            for (int n = 0; n < parameters.length; n++) statement.setObject(n + 1, parameters[n]);
            return statement;
          },
          (RowCallbackHandler)
              row -> {
                try {
                  json.writeStartObject();
                  for (int n = 1; n <= row.getMetaData().getColumnCount(); n++)
                    column(json, row, n, (UUID) parameters[0]);
                  json.writeEndObject();
                } catch (IOException error) {
                  throw new UncheckedIOException(error);
                }
              });
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
    json.writeEndArray();
  }

  private void column(JsonGenerator json, ResultSet row, int index, UUID owner)
      throws IOException, SQLException {
    String name = row.getMetaData().getColumnLabel(index);
    if (!Set.of(
            "details_ciphertext", "body_ciphertext", "new_email_ciphertext", "content_ciphertext")
        .contains(name)) {
      json.writeFieldName(name);
      value(json, row.getObject(index));
      return;
    }
    String encrypted = row.getString(index), label = name.replace("_ciphertext", "");
    if (encrypted == null) {
      json.writeNullField(label);
      return;
    }
    var field =
        switch (name) {
          case "details_ciphertext" -> PrivacyEvidenceCrypto.Field.REQUEST_DETAILS;
          case "body_ciphertext" -> PrivacyEvidenceCrypto.Field.RESPONSE_BODY;
          case "content_ciphertext" -> PrivacyEvidenceCrypto.Field.RETAINED_CONTENT;
          default -> PrivacyEvidenceCrypto.Field.EMAIL_TARGET;
        };
    String clear = evidence.open(field, owner, row.getObject("id", UUID.class), encrypted);
    if (!"content_ciphertext".equals(name)) {
      json.writeStringField(label, clear);
      return;
    }
    Set<String> allowed =
        switch (row.getString("category")) {
          case "profile" -> Set.of("name", "email", "birth_date", "phone");
          case "contract_identity" -> Set.of("name", "email", "phone");
          case "vehicle_identity" -> Set.of("id", "model", "color", "plate");
          case "driver_application" ->
              Set.of("id", "vehicle_id", "status", "decision", "created_at", "reviewed_at");
          case "security_audit" ->
              Set.of("id", "action", "resource_type", "resource_id", "created_at");
          default ->
              throw new IOException("Retained category is not available for automatic export.");
        };
    json.writeObjectFieldStart("content");
    try (var parser = new JsonFactory().createParser(clear)) {
      if (parser.nextToken() != JsonToken.START_OBJECT)
        throw new IOException("Retained identity is unavailable.");
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME)
          throw new IOException("Retained identity is invalid.");
        String property = parser.currentName();
        JsonToken token = parser.nextToken();
        if (allowed.contains(property)) {
          if (token != JsonToken.VALUE_STRING && token != JsonToken.VALUE_NULL)
            throw new IOException("Retained identity field is invalid.");
          json.writeFieldName(property);
          json.copyCurrentStructure(parser);
        } else parser.skipChildren();
      }
      if (parser.nextToken() != null) throw new IOException("Retained identity is invalid.");
    }
    json.writeEndObject();
  }

  private static void value(JsonGenerator json, Object value) throws IOException, SQLException {
    if (value == null) json.writeNull();
    else if (value instanceof Timestamp timestamp)
      json.writeString(timestamp.toInstant().toString());
    else if (value instanceof Boolean bool) json.writeBoolean(bool);
    else if (value instanceof Number number) json.writeNumber(number.toString());
    else if (value instanceof java.sql.Array array) {
      try {
        json.writeStartArray();
        for (Object item : (Object[]) array.getArray()) value(json, item);
        json.writeEndArray();
      } finally {
        array.free();
      }
    } else json.writeString(value.toString());
  }
}
