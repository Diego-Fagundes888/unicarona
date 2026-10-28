package com.unicarona.payment.repository;

import com.unicarona.payment.dto.MercadoPagoDto;
import com.unicarona.payment.dto.MercadoPagoDto.GatewayDetail;
import com.unicarona.shared.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owner-scoped payment projections. Provider calls and mutations stay in services. */
@Repository
public class MercadoPagoReadRepository {
  private final JdbcTemplate sql;

  public MercadoPagoReadRepository(JdbcTemplate sql) {
    this.sql = sql;
  }

  public GatewayDetail detail(UUID owner, UUID paymentId, String environment) {
    var result =
        sql.query(
            "SELECT m.*,CASE WHEN p.method='pix' AND privacy_order_checkout_current(p.id,m.state) THEN m.qr_code END AS visible_qr_code,CASE WHEN p.method='pix' AND privacy_order_checkout_current(p.id,m.state) THEN m.qr_base64 END AS visible_qr_base64,CASE WHEN p.method='card' AND m.status_detail='pending_challenge' AND privacy_order_checkout_current(p.id,m.state) THEN m.challenge_url END AS visible_challenge,c.payment_id IS NOT NULL AS card_submitted,coalesce(c.status,x.status) AS card_command_status,coalesce(c.last_error_code,x.last_error_code) AS last_error_code,p.status AS internal_status FROM payments p LEFT JOIN mercado_pago_orders m ON m.payment_id=p.id LEFT JOIN mercado_pago_card_commands c ON c.payment_id=p.id LEFT JOIN mercado_pago_pix_commands x ON x.payment_id=p.id WHERE p.id=? AND p.passenger_id=? AND p.provider='mercadopago'",
            (r, n) ->
                new GatewayDetail(
                    r.getString("state") == null
                        ? ("expired".equals(r.getString("internal_status"))
                            ? "EXPIRED"
                            : "failed".equals(r.getString("card_command_status"))
                                ? "REJECTED"
                                : r.getBoolean("card_submitted") ? "PROCESSING" : "CREATED")
                        : r.getString("state"),
                    r.getString("status_detail"),
                    r.getString("visible_qr_code"),
                    r.getString("visible_qr_base64"),
                    null,
                    environment,
                    r.getBoolean("card_submitted"),
                    r.getString("last_error_code"),
                    r.getString("visible_challenge")),
            paymentId,
            owner);
    ApiException.require(!result.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
    return result.getFirst();
  }

  public MercadoPagoDto.RefundReview refundReview(UUID owner) {
    return new MercadoPagoDto.RefundReview(
        Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id WHERE p.passenger_id=? AND (m.untracked_refunds OR m.chargeback_review))",
                Boolean.class,
                owner)));
  }

  public MercadoPagoDto.RefundDetail refunds(UUID owner, UUID transferId) {
    var rows =
        sql.query(
            """
            SELECT t.id,t.status,t.cents,
              coalesce(sum(r.amount_cents) FILTER(WHERE r.status='approved'),0) AS returned,
              coalesce(sum(r.amount_cents) FILTER(WHERE r.status='failed'),0) AS restored,
              coalesce(sum(r.amount_cents) FILTER(WHERE r.status IN ('pending','processing')),0) AS processing
            FROM transfers t JOIN mercado_pago_refunds r ON r.transfer_id=t.id
            WHERE t.id=? AND t.user_id=? GROUP BY t.id
            """,
            (row, n) ->
                new MercadoPagoDto.RefundDetail(
                    row.getObject("id", UUID.class),
                    row.getString("status"),
                    row.getInt("cents"),
                    row.getLong("returned"),
                    "pending".equals(row.getString("status")) ? 0 : row.getLong("restored"),
                    row.getLong("processing")
                        + ("pending".equals(row.getString("status"))
                            ? row.getLong("restored")
                            : 0)),
            transferId,
            owner);
    ApiException.require(!rows.isEmpty(), "TRANSFER_NOT_FOUND", "Devolução não encontrada.", 404);
    return rows.getFirst();
  }
}
