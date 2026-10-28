package com.unicarona.payment.controller;

import com.unicarona.payment.dto.FinanceDto;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.payment.service.PassengerDebtService;
import com.unicarona.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @Tag(name="Payments") @SecurityRequirement(name="cookieAuth")
public class WalletController {
    private final LedgerService ledger;
    private final PassengerDebtService debts;
    public WalletController(LedgerService ledger,PassengerDebtService debts) { this.ledger=ledger;this.debts=debts; }
    @GetMapping("/wallet") @Operation(summary="Saldo próprio calculado do ledger e das parcelas")
    public FinanceDto.Wallet wallet(@AuthenticationPrincipal Actor actor) { return ledger.wallet(actor.id()); }
    @GetMapping("/wallet/debt") @Operation(summary="Consultar valor devido próprio e bloqueio de novas compras")
    public PassengerDebtService.Summary debt(@AuthenticationPrincipal Actor actor) { return debts.summary(actor.id()); }
    @GetMapping("/admin/reconciliation") @PreAuthorize("hasRole('ADMIN')") @Operation(summary="Conferir conservação global dos valores recebidos")
    public FinanceDto.Reconciliation reconciliation() { return ledger.reconciliation(); }
}
