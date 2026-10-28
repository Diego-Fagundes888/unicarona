package com.unicarona.security;

/** Explicit official SDK origins; only the per-document nonce permits inline device profiling. */
public final class ContentSecurityPolicy {
    static final String NONCE_ATTRIBUTE="unicarona.cspNonce";
    private ContentSecurityPolicy() {}
    public static String value(String nonce) {
        String inline=nonce!=null && nonce.matches("[A-Za-z0-9_-]{32}")?" 'nonce-"+nonce+"'":"";
        return "default-src 'self'; script-src 'self'"+inline+" https://sdk.mercadopago.com https://http2.mlstatic.com; "
            +"style-src 'self' 'unsafe-inline'; img-src 'self' data: https://*.openstreetmap.org https://http2.mlstatic.com https://www.mercadolibre.com/jms/ https://www.mercadolivre.com/jms/; "
            +"frame-src https://www.openstreetmap.org https://secure-fields.mercadopago.com https://www.mercadopago.com.br https://www.mercadopago.com; "
            +"connect-src 'self' https://api.mercadopago.com https://sdk.mercadopago.com https://api.mercadolibre.com https://http2.mlstatic.com https://secure-fields.mercadopago.com https://api-static.mercadopago.com https://www.mercadolibre.com/jms/; "
            +"object-src 'none'; frame-ancestors 'none'";
    }
}
