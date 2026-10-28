package com.unicarona.support;

/** Public synthetic fixtures, isolated from any application's real credentials. */
public final class AuthTestSettings {
    private AuthTestSettings() {}
    public static final String PEPPER="Synthetic-Java-auth-test-pepper-2026";
    public static final String KEY="ab".repeat(32);
    public static final String SECRET="Synthetic-Java-JWT-only-test-secret-2026";
    public static final String ORIGIN="http://127.0.0.1:39002";
    public static final String WEBHOOK="Synthetic-reference-payment-only-secret-2026";
    public static final String SEED_PASSWORD="Synthetic seed test only 2026";
    public static String[] arguments(String databaseUrl) {
        return new String[]{"--DATABASE_URL="+databaseUrl,"--server.port=0","--spring.main.banner-mode=off","--logging.level.root=WARN",
            "--TOKEN_PEPPER="+PEPPER,"--PIN_ENCRYPTION_KEY="+KEY,"--JWT_SECRET="+SECRET,"--APP_ORIGIN="+ORIGIN,"--NODE_ENV=test",
            "--PAYMENT_PROVIDER=development","--EMAIL_PROVIDER=development","--PAYMENT_WEBHOOK_SECRET="+WEBHOOK,
            "--LEGAL_CONTROLLER_NAME=Synthetic responsible person","--LEGAL_PRIVACY_EMAIL=privacy@example.test","--LEGAL_CITY=Synthetic city","--LEGAL_STATE=GO","--LEGAL_COMMERCIAL_OPERATION=false"};
    }
}
