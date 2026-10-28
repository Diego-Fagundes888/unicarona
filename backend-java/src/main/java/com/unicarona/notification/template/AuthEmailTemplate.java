package com.unicarona.notification.template;

import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.provider.EmailProvider;
import java.net.URI;

/** Inline table layout, PNG branding with alt text, and system fonts; no JavaScript or remote CSS. */
public final class AuthEmailTemplate {
    private final EmailSettings settings;
    public AuthEmailTemplate(EmailSettings settings){this.settings=settings;}
    public record Content(String subject,String html,String text){@Override public String toString(){return "AuthEmailContent[redacted]";}}
    public Content render(EmailProvider.Message input){return render(input,null,null,null);}
    public Content renderClosure(EmailProvider.Message input,java.time.Instant earliestEraseAfter,String privacyContact){
        return renderClosure(input,earliestEraseAfter,privacyContact,null);
    }
    public Content renderClosure(EmailProvider.Message input,java.time.Instant earliestEraseAfter,String privacyContact,java.time.Instant registeredAt){
        if(!java.util.Set.of("closure_requested","closure_platform","closure_cancelled","closure_completed").contains(input.kind()))throw new IllegalArgumentException("Unsupported closure notice");
        return render(input,earliestEraseAfter,privacyContact,registeredAt);
    }
    private Content render(EmailProvider.Message input,java.time.Instant earliestEraseAfter,String privacyContact,java.time.Instant registeredAt){
        boolean verify="verify_email".equals(input.kind());
        boolean change="change_email".equals(input.kind()),requestedNotice="email_change_notice".equals(input.kind()),completedNotice="email_changed_notice".equals(input.kind());
        boolean closure=java.util.Set.of("closure_requested","closure_platform","closure_cancelled","closure_completed").contains(input.kind());
        boolean notice=requestedNotice||completedNotice||closure;
        if(!verify&&!change&&!notice&&!"reset_password".equals(input.kind()))throw new IllegalArgumentException("Unsupported authentication email");
        URI url=URI.create(input.url());
        boolean completedClosure="closure_completed".equals(input.kind());
        boolean validUrl=completedClosure?input.url().equals(settings.privacyUrl()):settings.frontendOrigin().equals(url.getScheme()+"://"+url.getAuthority())&&"/login.html".equals(url.getPath())&&url.getRawQuery()==null&&url.getUserInfo()==null
            &&(notice?url.getFragment()==null:url.getFragment()!=null&&url.getFragment().matches((verify?"verificar":change?"alterar-email":"redefinir")+"\\?token=[A-Za-z0-9_-]{43}"));
        if(!validUrl)throw new IllegalArgumentException("Invalid authentication email URL");
        if(input.content()!=null)return input.content(); // Frozen, encrypted at issue time; stable through retries/deploys.
        String subject=verify?"Confirme seu e-mail no UniCarona":"Recupere seu acesso ao UniCarona";
        String title=verify?"Confirme seu e-mail.":"Recupere seu acesso.";
        String description=verify?"Confirme seu endereço de e-mail para ativar sua conta e organizar suas caronas para a faculdade.":"Recebemos uma solicitação para alterar sua senha. Use o botão abaixo para escolher uma nova senha.";
        String action=verify?"Verificar meu e-mail":"Criar nova senha";
        if(change){subject="Confirme seu novo e-mail no UniCarona";title="Confirme o novo e-mail.";description="Recebemos um pedido para usar este endereço na sua conta. A troca só será concluída depois que você abrir o link, entrar na conta atual e confirmar sua senha.";action="Confirmar novo e-mail";}
        if(requestedNotice){subject="Pedido de troca de e-mail no UniCarona";title="Uma troca de e-mail foi solicitada.";description="Um pedido para alterar o e-mail de acesso foi feito na sua conta. A conclusão exige a senha e a confirmação do novo endereço. Se você não reconhece o pedido, entre na conta atual e altere sua senha, ou procure o suporte.";action="Acessar minha conta";}
        if(completedNotice){subject="Seu e-mail de acesso ao UniCarona foi alterado";title="Seu e-mail foi alterado.";description="O endereço de e-mail usado para acessar sua conta foi alterado após confirmação. As sessões anteriores foram encerradas. Se você não reconhece a mudança, procure o suporte informado no rodapé.";action="Acessar minha conta";}
        if("closure_requested".equals(input.kind())){subject="Recebemos seu pedido de exclusão no UniCarona";title="Pedido de exclusão recebido.";description="Novas combinações foram interrompidas. Você pode cancelar o pedido em Privacidade e dados antes de começar a remoção definitiva. Caronas e operações financeiras pendentes precisam do tratamento aplicável.";action="Consultar meu pedido";}
        if("closure_platform".equals(input.kind())){subject="Encerramento de conta iniciado pelo UniCarona";title="Encerramento de conta iniciado.";description="O UniCarona iniciou o encerramento da sua conta. Consulte a decisão e peça revisão em Privacidade e dados. A revisão da decisão é diferente do cancelamento de um pedido feito pelo titular; seus direitos e valores continuam preservados.";action="Consultar a decisão";}
        if("closure_cancelled".equals(input.kind())){subject="Encerramento de conta cancelado no UniCarona";title="Encerramento cancelado.";description="O encerramento da conta foi cancelado. Restrições de segurança que ainda existam continuam válidas. Ofertas pausadas, renovações automáticas e preferências promocionais não foram reativadas.";action="Consultar minha conta";}
        if(completedClosure){subject="Remoção do perfil concluída no UniCarona";title="Seu perfil foi removido.";description="Concluímos a remoção do seu perfil e dos arquivos abrangidos por esta etapa de exclusão. Registros ou documentos necessários a obrigações e casos em análise continuam com acesso restrito e prazos próprios.";action="Consultar privacidade";}
        int minutes=verify?settings.verificationMinutes():30;
        String hello=input.name()==null||input.name().isBlank()?"Olá!":"Olá, "+input.name().strip().split("\\s+")[0]+".";
        String security=verify?"Se você não criou esta conta, pode ignorar esta mensagem.":"Se você não solicitou a alteração, ignore esta mensagem. Sua senha permanece a mesma.";
        if(change)security="Se você não solicitou esta troca, ignore o link. Sua conta continua usando o endereço atual até a confirmação.";
        if(notice)security="Este é um aviso de segurança. Nunca envie senhas ou códigos por e-mail ou pelo suporte.";
        String validity=notice?"Você pode consultar sua conta ou procurar os canais de suporte abaixo.":"Este link expira em "+minutes+" minutos e só pode ser utilizado uma vez. Ao pedir outro link, use a mensagem mais recente.";
        if(closure){
            security="Este é um aviso essencial sobre a conta, independente de comunicações promocionais. Nunca envie senhas ou códigos ao suporte.";
            validity="Consulte no aplicativo o estado mais recente do pedido. Esta mensagem não confirma quitação de valores.";
            if(earliestEraseAfter!=null&&!completedClosure&&!"closure_cancelled".equals(input.kind()))validity="A remoção pode começar a partir de "+java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm",java.util.Locale.forLanguageTag("pt-BR")).withZone(java.time.ZoneId.of("America/Sao_Paulo")).format(earliestEraseAfter)+" (horário de Brasília), depois de tratar as pendências que impeçam o processamento. Consulte o estado atual no aplicativo.";
            if(completedClosure)validity=privacyContact==null||privacyContact.isBlank()?"Para exercer seus direitos ou tratar valores após o encerramento, use o canal de privacidade informado abaixo.":"Para exercer seus direitos ou tratar valores após o encerramento, escreva para "+privacyContact+". Esta confirmação não é uma quitação financeira.";
            if(completedClosure&&registeredAt!=null)validity="Encerramento registrado em "+java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm",java.util.Locale.forLanguageTag("pt-BR")).withZone(java.time.ZoneId.of("America/Sao_Paulo")).format(registeredAt)+" (horário de Brasília). "+validity;
        }
        String text="UniCarona\n\n"+hello+"\n\n"+(verify?"Confirme seu e-mail. ":"")+description+"\n\n"+action+":\n"+input.url()+"\n\n"+validity+"\n\n"+security+"\n\nUniCarona · Caronas para a faculdade\nSuporte: "+settings.supportUrl()+"\nPrivacidade: "+settings.privacyUrl()+"\nMensagem automática. Não responda a este e-mail.";
        String html="""
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>%s</title></head>
            <body style="margin:0;padding:0;background:#f3f3f3;color:#111;font-family:Arial,Helvetica,sans-serif">
            <div style="display:none;font-size:1px;line-height:1px;max-height:0;overflow:hidden">%s</div>
            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f3f3f3"><tr><td align="center" style="padding:24px 12px">
            <table role="presentation" width="560" cellspacing="0" cellpadding="0" style="width:100%%;max-width:560px;background:#fff;border:1px solid #dedede;border-radius:12px">
            <tr><td style="padding:32px 28px 24px;border-bottom:1px solid #dedede"><img src="%s/assets/brand/logo-email.png" width="220" height="50" alt="UniCarona" style="display:block;width:220px;max-width:100%%;height:auto;border:0;background:#fff;color:#111;font-size:26px;font-weight:700"></td></tr>
            <tr><td style="padding:32px 28px">
            <h1 style="margin:0 0 24px;font-size:30px;line-height:1.2;letter-spacing:-.6px">%s</h1>
            <p style="margin:0 0 12px;font-size:16px;line-height:1.6">%s</p><p style="margin:0 0 28px;font-size:16px;line-height:1.6">%s</p>
            <table role="presentation" cellspacing="0" cellpadding="0"><tr><td bgcolor="#111111" style="border-radius:8px;text-align:center"><a href="%s" style="display:inline-block;padding:16px 24px;border:1px solid #111;border-radius:8px;color:#fff;text-decoration:none;font-size:16px;font-weight:700">%s</a></td></tr></table>
            <p style="margin:24px 0 0;color:#626262;font-size:14px;line-height:1.6">%s</p>
            <p style="margin:24px 0 0;padding-top:24px;border-top:1px solid #dedede;color:#626262;font-size:14px;line-height:1.6">%s</p>
            <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#626262">Se o botão não abrir, <a href="%s" style="color:#087b5b;text-decoration:underline">acesse por este link</a>.</p></td></tr>
            <tr><td style="padding:24px 28px;background:#f3f3f3;border-top:1px solid #dedede;color:#626262;font-size:12px;line-height:1.6"><strong style="color:#111">UniCarona</strong> · Caronas para a faculdade<br><a href="%s" style="color:#626262">Suporte</a> &nbsp;·&nbsp; <a href="%s" style="color:#626262">Privacidade</a><br>Projeto independente, sem vínculo oficial com a UNICEPLAC.<br>Mensagem automática. Não responda a este e-mail.</td></tr>
            </table></td></tr></table></body></html>
            """.formatted(escape(subject),escape(action+(closure?". Aviso sobre sua conta.":notice?". Aviso de segurança.":". Link válido por "+minutes+" minutos.")),escape(settings.frontendOrigin()),escape(title),escape(hello),escape(description),escape(input.url()),escape(action),escape(validity),escape(security),escape(input.url()),escape(settings.supportUrl()),escape(settings.privacyUrl()));
        return new Content(subject,html,text);
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
