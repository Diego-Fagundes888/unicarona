-- A dispute does not prove that money or credit can be released again.
ALTER TABLE mercado_pago_orders ADD COLUMN chargeback_review boolean NOT NULL DEFAULT false;
UPDATE mercado_pago_orders m SET chargeback_review=true
WHERE m.provider_status='charged_back' OR m.status_detail='charged_back'
   OR EXISTS (
       SELECT 1 FROM webhook_events e
       WHERE e.provider='mercadopago' AND e.status='processed'
         AND e.payload->>'payment'=m.payment_id::text
         AND e.payload->>'state'='UNDER_REVIEW' AND e.payload->>'detail'='charged_back'
   );
