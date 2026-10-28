package com.unicarona.privacy.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** One definition for replay and confirmation of the principal account/profile suppression.
 * Purpose-retained contracts, evidence and financial identifiers are checked by their own policies. */
final class PrivacyProfileSuppression {
    private PrivacyProfileSuppression(){}
    static boolean needsScrub(JdbcTemplate sql,UUID owner){return Boolean.TRUE.equals(sql.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND (
          u.deleted_at IS NULL OR u.account_status<>'deleted' OR u.password_hash<>'disabled'
          OR u.name<>'Conta excluída' OR u.email<>'deleted-'||u.id::text||'@deleted.invalid'
          OR u.phone IS NOT NULL OR u.birth_date IS NOT NULL OR u.photo_file_id IS NOT NULL
          OR u.voluntarily_deactivated_at IS NOT NULL
          OR EXISTS(SELECT 1 FROM student_profiles p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM driver_profiles p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM sessions p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM auth_tokens p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM privacy_preferences p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM notifications p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM vehicles p WHERE p.owner_id=u.id AND (p.model<>'Veículo indisponível' OR p.color<>'Indisponível' OR p.plate<>'XXX0X00' OR p.deleted_at IS NULL OR p.status<>'retired'))
          OR EXISTS(SELECT 1 FROM driver_applications p WHERE p.user_id=u.id AND (p.decision IS NOT NULL OR p.reviewed_by IS NOT NULL OR p.identity_file_id IS NOT NULL OR p.license_file_id IS NOT NULL OR p.documents_retired_at IS NULL))
          OR EXISTS(SELECT 1 FROM ride_routes p WHERE p.driver_id=u.id AND (p.region<>'Região removida' OR p.meeting<>'Detalhes removidos' OR p.origin_address IS NOT NULL OR p.destination_address IS NOT NULL OR p.origin_lat IS NOT NULL OR p.origin_lng IS NOT NULL OR p.destination_lat IS NOT NULL OR p.destination_lng IS NOT NULL))
          OR EXISTS(SELECT 1 FROM rides p WHERE p.driver_id=u.id AND (p.notes<>'' OR p.proposal_reason IS NOT NULL))
          OR EXISTS(SELECT 1 FROM routines p WHERE p.driver_id=u.id AND (p.notes<>'' OR p.status<>'ended'))
          OR EXISTS(SELECT 1 FROM ride_requests p WHERE (p.passenger_id=u.id OR p.driver_id=u.id) AND p.reason IS NOT NULL)
          OR EXISTS(SELECT 1 FROM ride_reservations p WHERE (p.passenger_id=u.id OR p.driver_id=u.id) AND (p.absence_reason IS NOT NULL OR p.cancel_reason IS NOT NULL))
          OR EXISTS(SELECT 1 FROM ratings p WHERE (p.by_id=u.id OR p.other_id=u.id) AND p.comment IS NOT NULL)
          OR EXISTS(SELECT 1 FROM user_blocks p WHERE p.by_id=u.id OR p.other_id=u.id)
          OR EXISTS(SELECT 1 FROM waitlist p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM privacy_email_changes p WHERE p.user_id=u.id)
          OR EXISTS(SELECT 1 FROM idempotency_keys p WHERE p.actor_id=u.id)
          OR EXISTS(SELECT 1 FROM outbox_jobs p WHERE p.type='email' AND p.payload->>'ownerId'=u.id::text)))
        """,Boolean.class,owner));}
}
