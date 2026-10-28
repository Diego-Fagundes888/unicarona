package com.unicarona.auth.controller;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.auth.dto.SessionTokens;
import com.unicarona.auth.service.AuthService;
import com.unicarona.security.Actor;
import com.unicarona.security.ApiSecurityFilter;
import com.unicarona.user.dto.ProfileDto;
import com.unicarona.user.repository.ProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth") @Tag(name="Auth")
public class AuthController {
    private final AuthService auth;
    private final AuthSettings settings;
    private final ProfileRepository profiles;
    public AuthController(AuthService auth,AuthSettings settings,ProfileRepository profiles) { this.auth=auth; this.settings=settings; this.profiles=profiles; }
    public record LoginResponse(ProfileDto profile) {}
    public record EmailServiceStatus(boolean delayed,String message) {}
    @GetMapping("/email-service-status") @Operation(summary="Informar atraso geral de envio sem consultar endereços ou contas")
    public EmailServiceStatus emailServiceStatus() {
        boolean delayed=auth.emailServiceDelayed();
        return new EmailServiceStatus(delayed,delayed?"Alguns envios de e-mail estão atrasados. A solicitação continua registrada. Se a mensagem não chegar, solicite outro link após o prazo indicado.":"");
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary="Criar conta e enviar confirmação")
    public AuthDto.VerificationRequest register(@Valid @RequestBody AuthDto.Register body,HttpServletRequest request) {
        auth.limit("register:"+request.getRemoteAddr(),5,3600); auth.register(body);
        return new AuthDto.VerificationRequest("Se o cadastro puder ser concluído, enviaremos um link para confirmar o e-mail.",60);
    }
    @PostMapping("/login") @Operation(summary="Entrar e criar sessão segura")
    public LoginResponse login(@Valid @RequestBody AuthDto.Login body,HttpServletRequest request,HttpServletResponse response) {
        auth.limit("login-ip:"+request.getRemoteAddr(),40,900); auth.limit("login:"+body.email(),10,900);
        var result=auth.login(body.email(),body.password()); cookies(response,result.tokens()); return new LoginResponse(result.profile());
    }
    @PostMapping("/verify-email") @Operation(summary="Confirmar e-mail com token de uso único")
    public AuthDto.Message verify(@Valid @RequestBody AuthDto.Token body,HttpServletRequest request) { auth.limit("verify:"+request.getRemoteAddr(),30,900);auth.verify(body.token()); return new AuthDto.Message("E-mail confirmado. Entre na sua conta."); }
    @PostMapping("/resend-verification") @Operation(summary="Reenviar confirmação sem enumerar contas")
    public AuthDto.VerificationRequest resend(@Valid @RequestBody AuthDto.EmailInput body,HttpServletRequest request) {
        auth.limit("resend:"+request.getRemoteAddr(),20,3600); auth.resend(body.email()); return new AuthDto.VerificationRequest("Se necessário, enviaremos novas instruções para esse e-mail.",60);
    }
    @PostMapping("/forgot-password") @Operation(summary="Solicitar recuperação com resposta genérica")
    public AuthDto.Message forgot(@Valid @RequestBody AuthDto.EmailInput body,HttpServletRequest request) {
        auth.limit("forgot:"+request.getRemoteAddr(),5,3600); auth.forgot(body.email()); return new AuthDto.Message("Se existir uma conta para esse e-mail, enviaremos as instruções.");
    }
    @PostMapping("/reset-password") @Operation(summary="Redefinir senha e revogar sessões")
    public AuthDto.Message reset(@Valid @RequestBody AuthDto.Reset body,HttpServletRequest request) {
        auth.limit("reset:"+request.getRemoteAddr(),10,900); auth.reset(body.token(),body.password()); return new AuthDto.Message("Senha alterada. Entre novamente.");
    }
    @PostMapping("/refresh") @Operation(summary="Rotacionar sessão e detectar reutilização")
    public AuthDto.Message refresh(@Valid @RequestBody AuthDto.Empty body,HttpServletRequest request,HttpServletResponse response) {
        String refresh=ApiSecurityFilter.cookie(request,"uc_refresh"), csrf=request.getHeader("x-csrf-token");
        cookies(response,auth.refresh(refresh==null?"":refresh,csrf==null?"":csrf)); return new AuthDto.Message("Sessão renovada.");
    }
    @PostMapping("/logout") @Operation(summary="Encerrar toda a família da sessão",security=@SecurityRequirement(name="cookieAuth"))
    public AuthDto.Message logout(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AuthDto.Empty body,HttpServletResponse response) {
        auth.logout(actor); clear(response); return new AuthDto.Message("Você saiu da sua conta.");
    }
    @PostMapping("/change-password") @Operation(summary="Trocar senha mediante senha atual",security=@SecurityRequirement(name="cookieAuth"))
    public AuthDto.Message change(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AuthDto.Change body,HttpServletResponse response) {
        auth.change(actor,body.currentPassword(),body.password()); clear(response); return new AuthDto.Message("Senha alterada. Entre novamente.");
    }
    @GetMapping("/me") @Operation(summary="Consultar perfil da sessão atual",security=@SecurityRequirement(name="cookieAuth"))
    public ProfileDto me(@AuthenticationPrincipal Actor actor) { return profiles.find(actor.id()); }
    private void cookies(HttpServletResponse response,SessionTokens tokens) {
        cookie(response,"uc_access",tokens.access(),true,tokens.accessMinutes()*60L);
        cookie(response,"uc_refresh",tokens.refresh(),true,tokens.refreshDays()*86400L);
        cookie(response,"uc_csrf",tokens.csrf(),false,tokens.refreshDays()*86400L);
    }
    private void cookie(HttpServletResponse response,String name,String value,boolean httpOnly,long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(name,value).path("/").httpOnly(httpOnly).secure(settings.secure()).sameSite("Strict").maxAge(seconds).build().toString());
    }
    private void clear(HttpServletResponse response) {
        for (String name:new String[]{"uc_access","uc_refresh","uc_csrf"}) cookie(response,name,"",!"uc_csrf".equals(name),0);
    }
}
