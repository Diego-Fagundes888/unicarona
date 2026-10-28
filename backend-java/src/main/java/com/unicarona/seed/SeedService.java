package com.unicarona.seed;

import com.unicarona.security.CryptoService;
import com.unicarona.shared.TransactionRetries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SeedService {
    private final SeedSettings settings;
    private final SeedCatalog catalog;
    private final CryptoService crypto;
    private final SeedTransactions transactions;
    private final TransactionRetries retries;
    public SeedService(SeedSettings settings,SeedCatalog catalog,CryptoService crypto,SeedTransactions transactions,TransactionRetries retries) { this.settings=settings;this.catalog=catalog;this.crypto=crypto;this.transactions=transactions;this.retries=retries; }
    @Transactional(propagation=Propagation.NEVER) public SeedTransactions.Seeded run() {
        String password=settings.password();var input=catalog.load();String hash=crypto.hashPassword(password);return retries.call(() -> transactions.seed(input,hash));
    }
}
