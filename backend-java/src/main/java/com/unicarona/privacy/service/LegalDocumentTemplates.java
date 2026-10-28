package com.unicarona.privacy.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.privacy.config.PrivacySettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Repository-authored documents only. No user HTML, filesystem path or unverified business identity is loaded. */
@Component
public class LegalDocumentTemplates {
    public record Prepared(String slug,int revision,String title,String kind,List<String> scopes,boolean material,
        String summary,String content,String hash,String version){}
    public record Bundle(String publicationState,List<Prepared> documents){}
    private final ObjectMapper json;private final PrivacySettings privacy;private final AuthSettings auth;private final EmailSettings email;private final Environment env;
    public LegalDocumentTemplates(ObjectMapper json,PrivacySettings privacy,AuthSettings auth,EmailSettings email,Environment env){this.json=json;this.privacy=privacy;this.auth=auth;this.email=email;this.env=env;}
    public String publicationState(){try{return json.readTree(resource("catalog.json")).path("publicationState").asText();}catch(IOException error){throw new IllegalStateException("Invalid legal catalog.");}}
    public Bundle prepare(){
        if(privacy.controllerName().isBlank()||privacy.contactEmail().isBlank()||privacy.city().isBlank()||privacy.state().isBlank())throw new IllegalStateException("Legal publication requires the actual responsible person and contact.");
        try{
            var catalog=json.readTree(resource("catalog.json"));var result=new ArrayList<Prepared>();var slugs=new HashSet<String>();
            for(var item:catalog.path("documents")){
                String slug=item.path("slug").asText(),file=item.path("file").asText();int revision=item.path("revision").asInt();
                if(!slug.matches(com.unicarona.privacy.dto.LegalDto.SLUG)||!file.equals(slug+".md")||revision<1||!slugs.add(slug))throw new IllegalStateException("Invalid legal catalog descriptor.");
                String body=resource(file).replace("\r\n","\n").strip();for(var replacement:context().entrySet())body=body.replace("{{"+replacement.getKey()+"}}",replacement.getValue());
                if(body.contains("{{"))throw new IllegalStateException("Unresolved legal document context.");
                var canonical=new TreeMap<String,String>();canonical.put("body",body);canonical.put("title",item.path("title").asText());String content=json.writeValueAsString(canonical);
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
                var scopes=new ArrayList<String>();item.path("requiredFor").forEach(scope->scopes.add(scope.asText()));
                result.add(new Prepared(slug,revision,item.path("title").asText(),item.path("kind").asText(),List.copyOf(scopes),item.path("materialChange").asBoolean(),item.path("changeSummary").asText(),content,hash,"1."+revision+"."+hash.substring(0,16)));
            }
            if(!slugs.equals(Set.of("terms","privacy","driver-terms","participation","payments","safety")))throw new IllegalStateException("Incomplete legal catalog.");
            return new Bundle(catalog.path("publicationState").asText(),List.copyOf(result));
        }catch(IOException|java.security.GeneralSecurityException error){throw new IllegalStateException("Could not prepare legal documents.");}
    }
    private Map<String,String> context(){
        var values=new TreeMap<String,String>();values.put("controllerName",privacy.controllerName());values.put("controllerLocation",privacy.city()+"/"+privacy.state());values.put("privacyEmail",privacy.contactEmail());
        values.put("deletionGraceDays",""+privacy.deletionGraceDays());values.put("restrictionDays",""+privacy.registrationRestrictionDays());values.put("backupRetentionDays",""+privacy.backupRetentionDays());values.put("accessMinutes",""+auth.accessMinutes());values.put("refreshDays",""+auth.refreshDays());
        values.put("supplierDisclosure",privacy.commercialOperation()?"Identificação do responsável pelo fornecimento: "+privacy.providerIdentification()+". Endereço de atendimento: "+privacy.providerAddress()+".":"O serviço encontra-se em preparação e testes. A identificação e as informações exigíveis para uma oferta pública comercial precisam estar disponíveis antes dessa operação.");
        boolean test=!"live".equals(env.getProperty("MERCADO_PAGO_ENVIRONMENT","test"));
        values.put("operatingDisclosure",test?"Este ambiente utiliza pagamentos de teste/demonstração. Os valores e resultados de testes não representam transferência real de dinheiro nem serviço comercial de repasse habilitado.":"Este ambiente possui integração de pagamento configurada para operação real. Confira, antes de confirmar, o meio disponível, os valores e as condições efetivamente habilitadas.");
        values.put("payoutDisclosure","O repasse para motoristas não está habilitado comercialmente nesta versão. O registro de saldo não é promessa de Pix ou saque imediato. Antes de oferecer transporte com recebimento real, a operação precisa informar e disponibilizar um meio regular de repasse e conciliação.");
        values.put("hostingDisclosure",publicValue("LEGAL_HOSTING_DESCRIPTION","A versão atual é executada localmente no computador do responsável; banco e anexos ficam no armazenamento configurado para essa execução. Uma mudança para hospedagem externa exige atualizar a identificação do fornecedor e esta informação."));
        String mail=switch(email.provider()){case "resend"->"O envio de mensagens usa Resend para o endereço e o conteúdo necessários à comunicação.";case "smtp"->"O envio de mensagens usa "+("smtp.gmail.com".equalsIgnoreCase(email.host())?"Gmail/Google":"o servidor SMTP configurado pelo responsável")+" para o endereço e o conteúdo necessários à comunicação.";default->"Nesta configuração de demonstração, mensagens são registradas em caixa local de teste, sem entrega por serviço externo de e-mail.";};values.put("emailDisclosure",mail);
        values.put("transferDisclosure",publicValue("LEGAL_TRANSFER_DESCRIPTION","Serviços externos configurados podem envolver tratamento fora do Brasil. Na preparação desta operação, o responsável deve verificar os instrumentos contratuais, os papéis dos fornecedores e o mecanismo de transferência aplicável antes de uma operação comercial. Esta política não declara adesão a salvaguarda ou localização de servidor que não tenha sido conferida."));
        return values;
    }
    private String publicValue(String key,String fallback){String value=env.getProperty(key,"").strip();if(value.length()>3000)throw new IllegalStateException("Public legal context is too long.");return value.isEmpty()?fallback:value;}
    private String resource(String name)throws IOException {try(var input=getClass().getResourceAsStream("/legal/"+name)){if(input==null)throw new IOException("Missing legal resource.");byte[] data=input.readNBytes(262145);if(data.length>262144)throw new IOException("Legal resource exceeds supported size.");return new String(data,StandardCharsets.UTF_8);}}
}
